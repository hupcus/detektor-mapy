#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stažení skenů stabilního katastru z Archivu ÚAZK (ags.cuzk.gov.cz/archiv).

Archiv ÚAZK nemá georeferencovanou mozaiku — skeny leží v ImageServeru
``Archiv/archiv_nespojene_stable`` v **pixelovém rámu** (SR hlásí 5514, ale
souřadnice jsou 0..N; ověřeno 2026-08-20). Tenhle skript zautomatizuje to,
co se jinak kliká v prohlížeči:

1. vyžádá si anonymní token stejným GP jobem jako veřejná aplikace Archiv,
2. najde mapové listy podle katastru (``--katastr``) nebo souřadnice (``--lonlat``),
3. stáhne skeny přes ``exportImage`` s ``lockRaster`` mozaikou.

Výstup NENÍ georeferencovaný. Další krok je ruční: QGIS (GCP + TPS) →
``tools/warp_scan.py`` → PMTiles, nebo v aplikaci Vrstvy → „Přiložit sken…".

Příklady::

    python3 tools/archiv_fetch.py --katastr Úpice --out skeny/
    python3 tools/archiv_fetch.py --lonlat 16.0116 50.5123 --out skeny/ --full
    python3 tools/archiv_fetch.py --katastr Úpice --serie om --list-only

Server omezuje export na 15000×4100 px; ``--full`` proto stahuje vodorovné
pruhy a slepí je přes GDAL (musí být v PATH). Bez ``--full`` se sken zmenší
tak, aby se vešel do jednoho requestu.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Dict, List, Optional, Sequence, Tuple

try:
    import requests
except ImportError:  # pragma: no cover - závisí na prostředí
    requests = None  # type: ignore[assignment]

try:
    from sources import LICENCE_WARNING
except ImportError:  # spuštěno mimo adresář tools/
    from tools.sources import LICENCE_WARNING  # type: ignore

USER_AGENT = "DetektorMapy-archiv_fetch/1.0 (osobni pouziti; +https://github.com/hupcus)"

# Endpointy veřejné aplikace Archiv (ověřeno 2026-08-20 z jejího JS bundle).
# Env proměnné dovolují opravu bez zásahu do kódu, kdyby se zase stěhovaly.
GP_TOKEN_URL = os.environ.get(
    "DETEKTORMAPY_ARCHIV_GP",
    "https://ags.cuzk.gov.cz/arcgis2/rest/services/GenerateToken/Token/GPServer/GenerateToken",
)
TOKENS_URL = os.environ.get(
    "DETEKTORMAPY_ARCHIV_TOKENS", "https://ags.cuzk.gov.cz/arcgis4/tokens/"
)
KLADY_URL = os.environ.get(
    "DETEKTORMAPY_ARCHIV_KLADY",
    "https://ags.cuzk.gov.cz/arcgis4/rest/services/Archiv/klady/MapServer",
)
IMAGE_URL = os.environ.get(
    "DETEKTORMAPY_ARCHIV_IMAGE",
    "https://ags.cuzk.gov.cz/arcgis4/rest/services/Archiv/archiv_nespojene_stable/ImageServer",
)
REFERER = "https://ags.cuzk.gov.cz/archiv/"

# Vrstva 3 = klady map stabilního katastru (index z konfigurace aplikace, klíč cioc).
KLADY_LAYER = 3

# Limity exportu ImageServeru (f=json: maxImageWidth/maxImageHeight, 2026-08-20).
MAX_EXPORT_W = 15000
MAX_EXPORT_H = 4100

# Velikost pixelu v jednotkách pixelového rámu (pixelSizeX z metadat služby).
FRAME_PIXEL = 0.001968503937007874

# Série → (prefix atributů v kladech, popis). Sken v katalogu ImageServeru se
# jmenuje "<SIGN_INV>_<číslo listu>".
SERIES: Dict[str, Tuple[str, str]] = {
    "cio": ("cio_SIGN_INV", "Císařské povinné otisky 1:2880"),
    "om": ("om_SIGN_INV", "Originální mapy stabilního katastru 1:2880"),
    "kme": ("kme_SIGN_NOMEN", "Katastrální mapy evidenční 1:2880"),
}


# ---------------------------------------------------------------------------
# Čisté funkce (unit testy bez sítě)
# ---------------------------------------------------------------------------

def parse_token_from_messages(messages: Sequence[Dict[str, str]]) -> Optional[str]:
    """Vytáhne token ze zpráv GP jobu (aplikace ho posílá jako 'Token je: …')."""
    for message in messages:
        description = str(message.get("description", ""))
        if "Token je:" in description:
            token = description.split("Token je:", 1)[1].strip()
            if token:
                return token
    return None


def raster_prefix(attributes: Dict[str, object], serie: str) -> str:
    """Prefix jmen skenů v katalogu ImageServeru pro danou sérii."""
    try:
        field = SERIES[serie][0]
    except KeyError:
        raise ValueError("Neznámá série %r. Známé: %s" % (serie, ", ".join(sorted(SERIES))))
    value = str(attributes.get(field) or "").strip()
    if not value:
        raise ValueError(
            "List nemá vyplněný atribut %s — série %r pro tento katastr neexistuje." % (field, serie)
        )
    return value


def bbox_of_rings(rings: Sequence[Sequence[Sequence[float]]]) -> Tuple[float, float, float, float]:
    """Obálka polygonu z esriGeometryPolygon ``rings``."""
    xs = [point[0] for ring in rings for point in ring]
    ys = [point[1] for ring in rings for point in ring]
    if not xs:
        raise ValueError("Prázdná geometrie skenu.")
    return min(xs), min(ys), max(xs), max(ys)


def frame_to_pixels(bbox: Tuple[float, float, float, float]) -> Tuple[int, int]:
    """Rozměr skenu v pixelech: souřadnice rámu / velikost pixelu."""
    width = int(round((bbox[2] - bbox[0]) / FRAME_PIXEL))
    height = int(round((bbox[3] - bbox[1]) / FRAME_PIXEL))
    return width, height


def fit_size(width: int, height: int, max_w: int = MAX_EXPORT_W, max_h: int = MAX_EXPORT_H) -> Tuple[int, int]:
    """Zmenší rozměr tak, aby se vešel do jednoho exportu (zachová poměr stran)."""
    scale = min(1.0, max_w / float(width), max_h / float(height))
    return max(1, int(width * scale)), max(1, int(height * scale))


def plan_strips(
    bbox: Tuple[float, float, float, float],
    max_w: int = MAX_EXPORT_W,
    max_h: int = MAX_EXPORT_H,
) -> List[Tuple[Tuple[float, float, float, float], Tuple[int, int]]]:
    """Rozřeže sken na vodorovné pruhy plného rozlišení.

    Vrací [(bbox pruhu v rámu, (šířka, výška) v px)], odshora dolů, aby se
    pruhy lepily v pořadí čtení. Šířka celého skenu musí projít limitem —
    skeny ÚAZK jsou na výšku, takže limitem je výška 4100 px.
    """
    width_px, height_px = frame_to_pixels(bbox)
    if width_px > max_w:
        raise ValueError(
            "Sken je širší (%d px) než limit exportu %d px — pruhy by chtěly i svislé řezy."
            % (width_px, max_w)
        )
    strips = []
    rows = max(1, math.ceil(height_px / float(max_h)))
    strip_px = math.ceil(height_px / float(rows))
    for row in range(rows):
        top_px = row * strip_px
        bottom_px = min(height_px, top_px + strip_px)
        # Pixel 0 je nahoře; osa Y rámu roste nahoru.
        y_top = bbox[3] - top_px * FRAME_PIXEL
        y_bottom = bbox[3] - bottom_px * FRAME_PIXEL
        strips.append(
            ((bbox[0], y_bottom, bbox[2], y_top), (width_px, bottom_px - top_px))
        )
    return strips


def build_where_for_katastr(name: str) -> str:
    """WHERE pro hledání katastru podle názvu (case-insensitive, escapované ')."""
    safe = name.replace("'", "''")
    return "UPPER(NAZ_PUV_CS) = UPPER('%s') OR UPPER(cio_ZOBR_UZEMI) = UPPER('%s')" % (safe, safe)


# ---------------------------------------------------------------------------
# Síť
# ---------------------------------------------------------------------------

def _session() -> "requests.Session":
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Referer": REFERER})
    return session


def get_token(session: "requests.Session", timeout: float = 30.0) -> str:
    """Anonymní token stejným GP jobem, jakým si ho bere veřejná aplikace."""
    override = os.environ.get("DETEKTORMAPY_ARCHIV_TOKEN")
    if override:
        return override
    params = {
        "GenerateTokenURL": TOKENS_URL,
        "referer": REFERER,
        "parametr": "",
        "expiration": 60,
        "f": "json",
    }
    submitted = session.get(GP_TOKEN_URL + "/submitJob", params=params, timeout=timeout).json()
    job_id = submitted.get("jobId")
    if not job_id:
        raise RuntimeError("GenerateToken nevrátil jobId: %s" % submitted)
    for _ in range(15):
        time.sleep(2)
        job = session.get(
            "%s/jobs/%s" % (GP_TOKEN_URL, job_id), params={"f": "json"}, timeout=timeout
        ).json()
        status = job.get("jobStatus")
        if status == "esriJobSucceeded":
            token = parse_token_from_messages(job.get("messages", []))
            if token:
                return token
            raise RuntimeError("GP job doběhl, ale zprávy neobsahují token.")
        if status == "esriJobFailed":
            raise RuntimeError("GenerateToken selhal: %s" % job.get("messages"))
    raise RuntimeError("GenerateToken nedoběhl do 30 s.")


def find_sheets(
    session: "requests.Session",
    token: str,
    katastr: Optional[str],
    lonlat: Optional[Tuple[float, float]],
    timeout: float = 30.0,
) -> List[Dict[str, object]]:
    """Najde listy v kladech (vrstva 3) podle názvu katastru nebo WGS84 bodu."""
    params: Dict[str, object] = {
        "outFields": "*",
        "returnGeometry": "false",
        "f": "json",
        "token": token,
    }
    if lonlat is not None:
        params.update(
            geometry=json.dumps({"x": lonlat[0], "y": lonlat[1]}),
            geometryType="esriGeometryPoint",
            inSR=4326,
            spatialRel="esriSpatialRelIntersects",
        )
    else:
        params["where"] = build_where_for_katastr(katastr or "")
    url = "%s/%d/query" % (KLADY_URL, KLADY_LAYER)
    payload = session.get(url, params=params, timeout=timeout).json()
    if "error" in payload:
        raise RuntimeError("Dotaz na klady selhal: %s" % payload["error"])
    return [feature["attributes"] for feature in payload.get("features", [])]


def list_rasters(
    session: "requests.Session", token: str, prefix: str, timeout: float = 30.0
) -> List[Dict[str, object]]:
    """Skeny v katalogu ImageServeru: [{oid, name, bbox}] seřazené podle jména."""
    payload = session.get(
        IMAGE_URL + "/query",
        params={
            "where": "Name LIKE '%s%%'" % prefix.replace("'", "''"),
            "outFields": "OBJECTID,Name",
            "returnGeometry": "true",
            "f": "json",
            "token": token,
        },
        timeout=timeout,
    ).json()
    if "error" in payload:
        raise RuntimeError("Dotaz na katalog skenů selhal: %s" % payload["error"])
    rasters = []
    for feature in payload.get("features", []):
        rasters.append(
            {
                "oid": feature["attributes"]["OBJECTID"],
                "name": feature["attributes"]["Name"],
                "bbox": bbox_of_rings(feature["geometry"]["rings"]),
            }
        )
    rasters.sort(key=lambda r: str(r["name"]))
    return rasters


def export_image(
    session: "requests.Session",
    token: str,
    oid: int,
    bbox: Tuple[float, float, float, float],
    size: Tuple[int, int],
    out_path: str,
    timeout: float = 120.0,
) -> None:
    """Jeden exportImage s lockRaster mozaikou; ukládá JPEG."""
    response = session.get(
        IMAGE_URL + "/exportImage",
        params={
            "bbox": "%f,%f,%f,%f" % bbox,
            "size": "%d,%d" % size,
            "format": "jpg",
            "mosaicRule": json.dumps(
                {"mosaicMethod": "esriMosaicLockRaster", "lockRasterIds": [oid]}
            ),
            "f": "image",
            "token": token,
        },
        timeout=timeout,
    )
    content_type = response.headers.get("Content-Type", "")
    if response.status_code != 200 or "image" not in content_type:
        snippet = response.content[:200].decode("utf-8", "replace")
        raise RuntimeError("exportImage selhal (%s): %s" % (content_type, snippet))
    with open(out_path, "wb") as handle:
        handle.write(response.content)


def stitch_strips(strip_paths: List[str], strip_heights: List[int], width: int, out_path: str) -> None:
    """Slepí vodorovné pruhy pod sebe přes GDAL (v pixelovém prostoru)."""
    gdal_translate = shutil.which("gdal_translate")
    gdalbuildvrt = shutil.which("gdalbuildvrt")
    if not gdal_translate or not gdalbuildvrt:
        raise RuntimeError(
            "GDAL není v PATH (brew install gdal) — pruhy zůstávají po jednom: %s"
            % ", ".join(strip_paths)
        )
    with tempfile.TemporaryDirectory(prefix="archiv_stitch_") as tmp:
        tifs = []
        top = 0
        for index, (path, height) in enumerate(zip(strip_paths, strip_heights)):
            tif = os.path.join(tmp, "strip_%02d.tif" % index)
            # Fiktivní georeference: 1 jednotka = 1 pixel, osa Y dolů záporná.
            subprocess.run(
                [
                    gdal_translate, "-q", "-of", "GTiff",
                    "-a_ullr", "0", str(-top), str(width), str(-(top + height)),
                    path, tif,
                ],
                check=True,
            )
            tifs.append(tif)
            top += height
        vrt = os.path.join(tmp, "full.vrt")
        subprocess.run([gdalbuildvrt, "-q", vrt] + tifs, check=True)
        subprocess.run(
            [gdal_translate, "-q", "-of", "JPEG", "-co", "QUALITY=95", vrt, out_path],
            check=True,
        )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    """Stáhne skeny stabilního katastru pro katastr/souřadnici z Archivu ÚAZK."""
    parser = argparse.ArgumentParser(
        description="Skeny stabilního katastru z Archivu ÚAZK (bez georeference).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    where = parser.add_mutually_exclusive_group(required=True)
    where.add_argument("--katastr", help="název katastrálního území (např. Úpice)")
    where.add_argument(
        "--lonlat", nargs=2, type=float, metavar=("LON", "LAT"),
        help="WGS84 souřadnice uvnitř katastru",
    )
    parser.add_argument(
        "--serie", choices=sorted(SERIES), default="cio",
        help="mapová série (%s)" % "; ".join("%s = %s" % (k, v[1]) for k, v in sorted(SERIES.items())),
    )
    parser.add_argument("--out", default="skeny", help="výstupní adresář")
    parser.add_argument("--full", action="store_true",
                        help="plné rozlišení (pruhy + slepení přes GDAL)")
    parser.add_argument("--list-only", action="store_true",
                        help="jen vypsat nalezené listy, nic nestahovat")
    parser.add_argument("--timeout", type=float, default=60.0, help="timeout requestu v s")
    args = parser.parse_args(argv)

    if requests is None:
        print(
            "CHYBA: chybí modul 'requests'. Nainstaluj: "
            "python3 -m pip install -r tools/requirements.txt",
            file=sys.stderr,
        )
        return 2

    print(LICENCE_WARNING)
    print()

    session = _session()
    token = get_token(session, timeout=args.timeout)

    lonlat = tuple(args.lonlat) if args.lonlat else None
    sheets = find_sheets(session, token, args.katastr, lonlat, timeout=args.timeout)
    if not sheets:
        print("Nic nenalezeno. Zkus přesný název katastru, nebo --lonlat.", file=sys.stderr)
        return 1

    exit_code = 0
    for attributes in sheets:
        name = attributes.get("NAZ_PUV_CS") or attributes.get("cio_ZOBR_UZEMI")
        try:
            prefix = raster_prefix(attributes, args.serie)
        except ValueError as exc:
            print("%s: %s" % (name, exc), file=sys.stderr)
            exit_code = 1
            continue
        rasters = list_rasters(session, token, prefix, timeout=args.timeout)
        print("%s (%s, od %s): %d skenů [%s]" % (
            name, prefix, attributes.get("%s_DAT_OD" % args.serie, "?"), len(rasters),
            ", ".join(str(r["name"]) for r in rasters),
        ))
        if args.list_only:
            continue

        os.makedirs(args.out, exist_ok=True)
        for raster in rasters:
            bbox = raster["bbox"]
            width_px, height_px = frame_to_pixels(bbox)
            out_path = os.path.join(args.out, "%s.jpg" % raster["name"])
            if not args.full:
                size = fit_size(width_px, height_px)
                export_image(session, token, raster["oid"], bbox, size, out_path,
                             timeout=args.timeout)
                print("  %s: %dx%d px (zmenšeno z %dx%d)"
                      % (out_path, size[0], size[1], width_px, height_px))
                continue
            strips = plan_strips(bbox)
            strip_paths, strip_heights = [], []
            for index, (strip_bbox, strip_size) in enumerate(strips):
                strip_path = os.path.join(
                    args.out, "%s_strip%02d.jpg" % (raster["name"], index)
                )
                export_image(session, token, raster["oid"], strip_bbox, strip_size,
                             strip_path, timeout=args.timeout)
                strip_paths.append(strip_path)
                strip_heights.append(strip_size[1])
            if len(strip_paths) == 1:
                os.replace(strip_paths[0], out_path)
            else:
                stitch_strips(strip_paths, strip_heights, width_px, out_path)
                for path in strip_paths:
                    os.remove(path)
            print("  %s: %dx%d px (plné rozlišení, %d pruhů)"
                  % (out_path, width_px, height_px, len(strips)))

    if not args.list_only:
        print()
        print("Skeny NEJSOU georeferencované. Další krok:")
        print("  a) rychle: v aplikaci Vrstvy → „Přiložit sken…\" a zarovnat 4 rohy, nebo")
        print("  b) pořádně: QGIS (GCP + TPS) → tools/warp_scan.py → tools/build_pmtiles.py")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
