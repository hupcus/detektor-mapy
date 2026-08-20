#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stažení polygonů ÚAN (NPÚ, ArcGIS FeatureServer) do GeoJSON (F4-3).

ÚAN = území s archeologickými nálezy. V aplikaci slouží jako právní kontext
(varování při vstupu do ÚAN I/II, viz PLAN.md sekce 9 a 10).

**POZOR — endpoint je nutné ověřit.** PLAN.md sekce 3 uvádí jen „ArcGIS služby
NPÚ (isad.npu.cz / npu.maps.arcgis.com, item ``4e5f269e38004377bdc5fa8a6cbec58d``)"
s poznámkou „ověřit aktuální endpoint při implementaci". URL v ``tools/sources.py``
je proto **pracovní odhad**; skutečnou adresu zjistíš z portálové položky
(``--discover``) a nastavíš přes ``--url`` nebo env ``DETEKTORMAPY_UAN_URL``.

Stránkování řeší ``resultOffset``/``resultRecordCount`` — ArcGIS vrací najednou
typicky 1000–2000 prvků a příznak ``exceededTransferLimit``.

Příklady::

    python3 tools/uan_fetch.py --discover
    python3 tools/uan_fetch.py --bbox 14.45 49.20 15.10 49.60 --out data/uan_tabor.geojson
    python3 tools/uan_fetch.py --where "KRAJ='Jihočeský'" --out data/uan_jck.geojson
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from typing import Any, Dict, List, Optional, Sequence

try:
    import requests
except ImportError:  # pragma: no cover
    requests = None  # type: ignore[assignment]

try:
    from sources import get_source
except ImportError:  # spuštěno mimo adresář tools/
    from tools.sources import get_source  # type: ignore

USER_AGENT = "DetektorMapy-uan_fetch/1.0 (osobni pouziti)"
DEFAULT_PAGE_SIZE = 1000

VERIFY_HINT = (
    "TODO/OVĚŘIT: endpoint ÚAN není v PLAN.md potvrzený. Postup:\n"
    "  1) python3 tools/uan_fetch.py --discover\n"
    "  2) z výpisu vyber URL vrstvy (…/FeatureServer/<n> nebo …/MapServer/<n>)\n"
    "  3) spusť s --url <URL>, nebo trvale: export DETEKTORMAPY_UAN_URL=<URL>\n"
    "     (případně přepiš zdroj 'uan_npu' v tools/sources.py a nastav verified=True)"
)


def _session():
    if requests is None:
        raise RuntimeError(
            "Chybí modul 'requests'. Nainstaluj: python3 -m pip install -r tools/requirements.txt"
        )
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept": "application/json"})
    return session


def discover_item(item_id: str, timeout: float = 30.0) -> Dict[str, Any]:
    """Zjistí z ArcGIS portálu, jaké služby daná položka nabízí."""
    session = _session()
    base = "https://www.arcgis.com/sharing/rest/content/items/%s" % item_id
    out: Dict[str, Any] = {"item_id": item_id}
    try:
        response = session.get(base, params={"f": "json"}, timeout=(10.0, timeout))
        out["item"] = response.json()
    except Exception as exc:  # noqa: BLE001 - discovery smí selhat
        out["item_error"] = str(exc)[:200]
    try:
        response = session.get(base + "/data", params={"f": "json"}, timeout=(10.0, timeout))
        out["data"] = response.json()
    except Exception as exc:  # noqa: BLE001
        out["data_error"] = str(exc)[:200]
    return out



def _with_layer(url: str, layer: str) -> str:
    """Doplní index vrstvy, když URL končí na kořeni MapServeru/FeatureServeru.

    Zdroj v ``sources.py`` je záměrně kořen služby (dá se z něj vypsat seznam vrstev),
    ale ``/query`` existuje jen na konkrétní vrstvě.
    """
    trimmed = url.rstrip("/")
    if re.search(r"/(Map|Feature)Server$", trimmed, re.IGNORECASE):
        return "%s/%s" % (trimmed, layer)
    return trimmed

def service_metadata(url: str, timeout: float = 30.0) -> Dict[str, Any]:
    """Načte ``?f=json`` metadata vrstvy/služby."""
    session = _session()
    response = session.get(url, params={"f": "json"}, timeout=(10.0, timeout))
    response.raise_for_status()
    data = response.json()
    if isinstance(data, dict) and "error" in data:
        raise RuntimeError("ArcGIS error: %s" % data["error"])
    return data


def query_features(url: str, where: str = "1=1", bbox: Optional[Sequence[float]] = None,
                   out_fields: str = "*", page_size: int = DEFAULT_PAGE_SIZE,
                   max_features: int = 0, timeout: float = 60.0,
                   out_sr: int = 4326, retries: int = 3,
                   verbose: bool = True) -> Dict[str, Any]:
    """Stáhne prvky s postupným stránkováním přes ``resultOffset``.

    Vrací GeoJSON FeatureCollection. Jednotlivá selhaná stránka se zopakuje;
    když ani opakování nepomůže, funkce vrátí, co se stihlo stáhnout.
    """
    session = _session()
    query_url = url.rstrip("/") + "/query"
    features: List[Dict[str, Any]] = []
    offset = 0

    while True:
        params: Dict[str, Any] = {
            "where": where,
            "outFields": out_fields,
            "returnGeometry": "true",
            "outSR": str(out_sr),
            "f": "geojson",
            "resultOffset": str(offset),
            "resultRecordCount": str(page_size),
        }
        if bbox:
            params["geometry"] = "%f,%f,%f,%f" % tuple(bbox)
            params["geometryType"] = "esriGeometryEnvelope"
            params["inSR"] = "4326"
            params["spatialRel"] = "esriSpatialRelIntersects"

        payload: Optional[Dict[str, Any]] = None
        for attempt in range(retries + 1):
            try:
                response = session.get(query_url, params=params, timeout=(10.0, timeout))
                response.raise_for_status()
                payload = response.json()
                break
            except Exception as exc:  # noqa: BLE001 - síť/JSON smí selhat
                if attempt >= retries:
                    print("  ! stránka offset=%d selhala: %s" % (offset, str(exc)[:160]),
                          file=sys.stderr)
                else:
                    time.sleep(1.5 * (attempt + 1))
        if payload is None:
            break
        if isinstance(payload, dict) and "error" in payload:
            print("  ! ArcGIS error: %s" % payload["error"], file=sys.stderr)
            break

        page = payload.get("features") or []
        features.extend(page)
        if verbose:
            print("  offset %6d  +%d prvků  (celkem %d)" % (offset, len(page), len(features)))
        if max_features and len(features) >= max_features:
            features = features[:max_features]
            break
        exceeded = bool(payload.get("exceededTransferLimit") or
                        payload.get("properties", {}).get("exceededTransferLimit"))
        if not page or (len(page) < page_size and not exceeded):
            break
        offset += len(page)

    return {
        "type": "FeatureCollection",
        "features": features,
        "properties": {
            "source": url,
            "where": where,
            "bbox": list(bbox) if bbox else None,
            "fetched_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "generated_by": "tools/uan_fetch.py",
            "note": "ÚAN — území s archeologickými nálezy (NPÚ). Endpoint ověř dle README.",
        },
    }


def main(argv: Optional[List[str]] = None) -> int:
    """Stáhne polygony ÚAN do GeoJSON se stránkováním."""
    parser = argparse.ArgumentParser(
        description="Stažení ÚAN polygonů z ArcGIS služby NPÚ (F4-3).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        epilog=VERIFY_HINT,
    )
    parser.add_argument("--url", help="URL vrstvy (…/MapServer/0); jinak zdroj uan_npu")
    parser.add_argument("--layer", help="index vrstvy: 0=ÚAN I, 1=ÚAN II, 2=pásmo II, 3=ÚAN IV")
    parser.add_argument("--bbox", nargs=4, type=float, metavar=("W", "S", "E", "N"),
                        help="omezit na bbox ve WGS84")
    parser.add_argument("--kraj", help="zkratka pro --where s filtrem na kraj (viz --fields)")
    parser.add_argument("--kraj-field", default="KRAJ", help="název sloupce s krajem")
    parser.add_argument("--where", default="1=1", help="vlastní SQL filtr ArcGIS")
    parser.add_argument("--out", help="výstupní GeoJSON (bez něj se píše na stdout)")
    parser.add_argument("--page-size", type=int, default=DEFAULT_PAGE_SIZE,
                        help="počet prvků na stránku (resultRecordCount)")
    parser.add_argument("--max-features", type=int, default=0, help="strop na počet prvků (0 = bez)")
    parser.add_argument("--timeout", type=float, default=60.0, help="timeout requestu")
    parser.add_argument("--fields", action="store_true", help="jen vypsat sloupce vrstvy a skončit")
    parser.add_argument("--discover", action="store_true",
                        help="dohledat skutečný endpoint z portálové položky NPÚ")
    parser.add_argument("--quiet", action="store_true", help="bez průběžného výpisu stránek")
    args = parser.parse_args(argv)

    if requests is None:
        print("CHYBA: chybí modul 'requests' (pip install -r tools/requirements.txt)",
              file=sys.stderr)
        return 2

    source = get_source("uan_npu")
    url = _with_layer(args.url or source.effective_url, args.layer or source.layer or "0")

    if args.discover:
        item_id = source.extra.get("portal_item", "")
        print("Dohledávám služby položky %s na ArcGIS Online…\n" % item_id)
        info = discover_item(item_id)
        print(json.dumps(info, indent=2, ensure_ascii=False)[:8000])
        print("\n" + VERIFY_HINT)
        return 0

    if not source.verified and not args.url and not os.environ.get("DETEKTORMAPY_UAN_URL"):
        print("POZOR: používám NEOVĚŘENÝ endpoint %s" % url, file=sys.stderr)
        print(VERIFY_HINT + "\n", file=sys.stderr)

    if args.fields:
        try:
            meta = service_metadata(url, args.timeout)
        except Exception as exc:  # noqa: BLE001
            print("CHYBA: nelze načíst metadata %s: %s" % (url, str(exc)[:200]), file=sys.stderr)
            print("\n" + VERIFY_HINT, file=sys.stderr)
            return 1
        print("Vrstva: %s (%s)" % (meta.get("name", "?"), meta.get("geometryType", "?")))
        print("Prvků:  %s" % meta.get("maxRecordCount", "?") + " max na dotaz")
        for field in meta.get("fields", []):
            print("  %-24s %-22s %s" % (field.get("name", ""), field.get("type", ""),
                                        field.get("alias", "")))
        return 0

    where = args.where
    if args.kraj:
        where = "%s='%s'" % (args.kraj_field, args.kraj.replace("'", "''"))

    print("Zdroj:  %s" % url, file=sys.stderr)
    print("Filtr:  %s%s" % (where, (" + bbox %s" % (args.bbox,)) if args.bbox else ""),
          file=sys.stderr)
    collection = query_features(
        url, where=where, bbox=args.bbox, page_size=args.page_size,
        max_features=args.max_features, timeout=args.timeout, verbose=not args.quiet,
    )
    count = len(collection["features"])
    if count == 0:
        print("VAROVÁNÍ: nestáhly se žádné prvky — zkontroluj endpoint a filtr.", file=sys.stderr)
        print(VERIFY_HINT, file=sys.stderr)

    text = json.dumps(collection, ensure_ascii=False)
    if args.out:
        directory = os.path.dirname(os.path.abspath(args.out))
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("Uloženo %d prvků do %s (%.1f kB)"
              % (count, args.out, len(text.encode("utf-8")) / 1024.0), file=sys.stderr)
    else:
        print(text)
    return 0 if count else 1


if __name__ == "__main__":
    sys.exit(main())
