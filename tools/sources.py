#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Registr datových zdrojů projektu DetektorMapy (single source of truth).

Odpovídá tabulce v PLAN.md, sekce 3. Všechny ostatní skripty v ``tools/``
importují tenhle modul — URL, atribuce ani zoomy se nikde jinde nepíšou natvrdo.

Použití z příkazové řádky::

    python3 tools/sources.py            # tabulka zdrojů
    python3 tools/sources.py --json     # strojově čitelný výpis
    python3 tools/sources.py --id ii_vm # detail jednoho zdroje
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional

__all__ = [
    "Source",
    "SOURCES",
    "SOURCES_BY_ID",
    "all_sources",
    "get_source",
    "p1_sources",
    "LICENCE_WARNING",
]

# ---------------------------------------------------------------------------
# Licenční varování z PLAN.md sekce 3 — vypisují ho fetch_tiles i build_pmtiles.
# ---------------------------------------------------------------------------
LICENCE_WARNING = (
    "LICENCE: Prohlížecí služby ČÚZK/CENIA jsou zdarma, ale NEJSOU určeny ke "
    "komerčnímu užití a redistribuce vyžaduje souhlas. Tato data používej výhradně "
    "pro osobní potřebu, dlaždice drž lokálně. NIKDY nepublikuj APK ani PMTiles s daty "
    "veřejně. U vojenských mapování uváděj: © Rakouský státní archiv / "
    "Laboratoř geoinformatiky UJEP."
)

# Typy zdrojů, kterým rozumí fetch_tiles.py / check_endpoints.py
SOURCE_TYPES = ("wmts", "wms", "arcgis-wmts", "arcgis-rest", "atom")


@dataclass
class Source:
    """Jeden datový zdroj (vrstva) — vše, co pipeline potřebuje ke stažení."""

    id: str
    title: str
    type: str                      # jeden z SOURCE_TYPES
    url: str                       # základní endpoint služby
    layer: str = ""                # název vrstvy (WMS LAYERS / WMTS LAYER / ArcGIS layer id)
    fmt: str = "image/png"         # MIME formát dlaždice
    ext: str = "png"               # přípona souboru na disku
    attribution: str = ""
    crs: str = "EPSG:3857"         # nativní CRS služby
    min_zoom: int = 8
    max_zoom: int = 16
    priority: str = "P2"           # P1 = bez ní projekt nedává smysl
    verified: bool = True          # False = endpoint je v PLAN.md označen "ověřit"
    notes: str = ""
    # Volitelné doplňky (nepovinné, závislé na typu):
    wms_url: Optional[str] = None          # alternativní WMS endpoint téhož obsahu
    capabilities_url: Optional[str] = None  # explicitní URL na GetCapabilities/metadata
    tile_template: Optional[str] = None     # RESTful WMTS/XYZ šablona s {z}/{x}/{y}
    tilematrixset: Optional[str] = None     # WMTS TILEMATRIXSET (ověř přes check_endpoints)
    styles: List[str] = field(default_factory=list)
    env_override: Optional[str] = None      # jméno env proměnné, která přebije `url`
    extra: Dict[str, str] = field(default_factory=dict)

    # -- odvozené vlastnosti ------------------------------------------------
    @property
    def effective_url(self) -> str:
        """URL po aplikaci případného env override (kvůli neověřeným endpointům)."""
        if self.env_override:
            override = os.environ.get(self.env_override)
            if override:
                return override
        return self.url

    def get_capabilities_url(self) -> str:
        """URL, na kterou se ptá check_endpoints.py (GetCapabilities / ?f=json)."""
        if self.capabilities_url:
            return self.capabilities_url
        base = self.effective_url
        if self.type in ("wmts", "arcgis-wmts"):
            sep = "&" if "?" in base else "?"
            return base + sep + "SERVICE=WMTS&REQUEST=GetCapabilities&VERSION=1.0.0"
        if self.type == "wms":
            sep = "&" if "?" in base else "?"
            return base + sep + "SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"
        if self.type == "arcgis-rest":
            sep = "&" if "?" in base else "?"
            return base + sep + "f=json"
        return base

    def to_dict(self) -> Dict[str, object]:
        d = asdict(self)
        d["effective_url"] = self.effective_url
        d["capabilities"] = self.get_capabilities_url()
        return d


# ---------------------------------------------------------------------------
# Vlastní registr. Pořadí = pořadí v PLAN.md sekce 3.
# Nic, co není v PLAN.md, se sem nepřidává. Kde PLAN.md říká "ověřit",
# je verified=False a env_override pro rychlou opravu bez zásahu do kódu.
# ---------------------------------------------------------------------------

_CENIA_ATTR = "© CENIA / Rakouský státní archiv, Laboratoř geoinformatiky UJEP"
_CUZK_ATTR = "© ČÚZK"

SOURCES: List[Source] = [
    Source(
        id="ii_vm",
        title="II. vojenské mapování 1836–52 (CENIA)",
        type="wmts",
        url="https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wmts",
        wms_url="https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wms",
        layer="II_vojenske_mapovani",
        fmt="image/png",
        ext="png",
        attribution=_CENIA_ATTR,
        crs="EPSG:5514",
        min_zoom=8,
        max_zoom=16,
        priority="P1",
        tilematrixset="GoogleMapsCompatible",
        tile_template=(
            "https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wmts/1.0.0/"
            "{layer}/default/{tilematrixset}/{z}/{y}/{x}.png"
        ),
        notes=(
            "Celá ČR, pro detektoráře klíčová vrstva. MapCache umí RESTful i KVP GetTile. "
            "Přesné jméno LAYER a TILEMATRIXSET ověř: "
            "`python3 tools/check_endpoints.py --id ii_vm --capabilities`."
        ),
    ),
    Source(
        id="iii_vm",
        title="III. vojenské mapování — speciálky 1:75 000 (CENIA)",
        type="wmts",
        url="https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wmts",
        wms_url="https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wms",
        layer="III_vojenske_mapovani",
        fmt="image/png",
        ext="png",
        attribution=_CENIA_ATTR,
        crs="EPSG:5514",
        min_zoom=8,
        max_zoom=16,
        priority="P1",
        tilematrixset="GoogleMapsCompatible",
        tile_template=(
            "https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wmts/1.0.0/"
            "{layer}/default/{tilematrixset}/{z}/{y}/{x}.png"
        ),
        notes="Celá ČR. Stejné poznámky k LAYER/TILEMATRIXSET jako u ii_vm.",
    ),
    Source(
        id="dmr5g",
        title="DMR 5G — stínovaný reliéf (ČÚZK, ImageServer WMS)",
        type="wms",
        url="https://ags.cuzk.gov.cz/arcgis2/services/dmr5g/ImageServer/WMSServer",
        layer="dmr5g:GrayscaleHillshade",
        fmt="image/png",
        ext="png",
        attribution=_CUZK_ATTR,
        crs="EPSG:3857",
        min_zoom=10,
        max_zoom=17,
        priority="P1",
        styles=["GrayscaleHillshade", "SlopeRGBMap"],
        notes=(
            "LiDAR odvozený reliéf. Přesné jméno LAYERS ověř přes GetCapabilities "
            "(`--capabilities`); u ImageServer WMS bývá vrstva '0' nebo název stylu. "
            "Surová LAZ data viz zdroj dmr5g_atom -> tools/dmr5g_hillshade.py (Fáze 5)."
        ),
    ),
    Source(
        id="ortofoto",
        title="Ortofoto ČR (ČÚZK, ArcGIS WMTS)",
        type="arcgis-wmts",
        url="https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer/WMTS",
        layer="ORTOFOTO",
        fmt="image/jpeg",
        ext="jpg",
        attribution=_CUZK_ATTR,
        crs="EPSG:5514",
        min_zoom=10,
        max_zoom=18,
        priority="P1",
        tilematrixset="default028mm",
        tile_template=(
            "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer/WMTS/tile/"
            "1.0.0/{layer}/default/{tilematrixset}/{z}/{y}/{x}.jpg"
        ),
        capabilities_url=(
            "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer/WMTS/"
            "1.0.0/WMTSCapabilities.xml"
        ),
        notes=(
            "Referenční podklad pro kalibraci (GCP editor, Fáze 3). "
            "ArcGIS MapServer nabízí i XYZ cache: .../MapServer/tile/{z}/{y}/{x} — "
            "pozor, ta je v tilingu služby, ne nutně v GoogleMapsCompatible."
        ),
    ),
    Source(
        id="cisarske_jck",
        title="Císařské otisky — Jihočeský kraj",
        type="arcgis-wmts",
        url="https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/cisarske_otisky/MapServer/WMTS",
        layer="podkladove_cisarske_otisky",
        fmt="image/png",
        ext="png",
        attribution="© Jihočeský kraj / ČÚZK",
        crs="EPSG:5514",
        min_zoom=10,
        max_zoom=18,
        priority="P2",
        tilematrixset="default028mm",
        capabilities_url=(
            "https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/"
            "cisarske_otisky/MapServer/WMTS/1.0.0/WMTSCapabilities.xml"
        ),
        notes=(
            "Bezešvá mozaika, nativně EPSG:5514 — TileMatrixSet tedy NENÍ "
            "GoogleMapsCompatible a dlaždice nelze brát 1:1 jako XYZ. "
            "Pro PMTiles použij WMS/export cestu s reprojekcí (fetch_tiles.py --via-wms) "
            "nebo si šablonu ověř přes --capabilities. Klíčové pro okres Tábor."
        ),
        extra={"rest_url": "https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/cisarske_otisky/MapServer"},
    ),
    Source(
        id="cisarske_msk",
        title="Císařské otisky — Moravskoslezský kraj",
        type="arcgis-wmts",
        url="https://gis2.msk.cz/arcgis/rest/services/podklad/podklad_cis_otisky/MapServer/WMTS",
        layer="podklad_podklad_cis_otisky",
        fmt="image/png",
        ext="png",
        attribution="© Moravskoslezský kraj / ČÚZK",
        crs="EPSG:5514",
        min_zoom=10,
        max_zoom=18,
        priority="P2",
        tilematrixset="default028mm",
        capabilities_url=(
            "https://gis2.msk.cz/arcgis/rest/services/podklad/podklad_cis_otisky/"
            "MapServer/WMTS/1.0.0/WMTSCapabilities.xml"
        ),
        notes="Stejná poznámka k EPSG:5514 jako u cisarske_jck.",
        extra={"rest_url": "https://gis2.msk.cz/arcgis/rest/services/podklad/podklad_cis_otisky/MapServer"},
    ),
    Source(
        id="ii_vm_ujep",
        title="II. vojenské mapování — záloha (oldmaps geolab, UJEP)",
        type="wms",
        url="http://mapserver.ujep.cz/ArcGIS/services/Geolab/IIVM_WMS/MapServer/WMSServer",
        layer="0",
        fmt="image/png",
        ext="png",
        attribution="© Rakouský státní archiv / Laboratoř geoinformatiky UJEP",
        crs="EPSG:4326",
        min_zoom=8,
        max_zoom=15,
        priority="P2",
        notes=(
            "Záloha pro případ výpadku CENIA. Pouze HTTP (bez TLS). "
            "Jméno vrstvy ověř přes GetCapabilities."
        ),
    ),
    Source(
        id="uan_npu",
        title="ÚAN — území s archeologickými nálezy (NPÚ, ArcGIS FeatureServer)",
        type="arcgis-rest",
        url="https://geoportal.npu.cz/arcgis/rest/services/Tematicke/CP_UAN/MapServer",
        layer="0",
        fmt="application/json",
        ext="geojson",
        attribution="© Národní památkový ústav",
        crs="EPSG:5514",
        min_zoom=8,
        max_zoom=16,
        priority="P1",
        verified=True,
        env_override="DETEKTORMAPY_UAN_URL",
        notes=(
            "OVĚŘENO 2026-08-20: item 4e5f269e38004377bdc5fa8a6cbec58d na arcgis.com "
            "ukazuje na tuto Map Service. Nativní CRS je EPSG:5514 (wkid 102067), "
            "capabilities Map,Query,Data. Vrstvy: 0 = kategorie I (prokázaná), "
            "1 = kategorie II (předpokládaná), 2 = kategorie II (pásmo), "
            "3 = kategorie IV (vytěžená). Query vrací geometrie, které uan_fetch.py "
            "reprojektuje do WGS84. URL lze přebít env DETEKTORMAPY_UAN_URL."
        ),
        extra={
            "portal_item": "4e5f269e38004377bdc5fa8a6cbec58d",
            "portal_item_url": (
                "https://www.arcgis.com/sharing/rest/content/items/"
                "4e5f269e38004377bdc5fa8a6cbec58d?f=json"
            ),
        },
    ),
    Source(
        id="dmr5g_atom",
        title="DMR 5G — surová LAZ data (ČÚZK ATOM feed)",
        type="atom",
        url="https://atom.cuzk.gov.cz/DMR5G-SJTSK/DMR5G-SJTSK.xml",
        layer="",
        fmt="application/xml",
        ext="laz",
        attribution=_CUZK_ATTR,
        crs="EPSG:5514",
        min_zoom=12,
        max_zoom=18,
        priority="P2",
        capabilities_url="https://atom.cuzk.gov.cz/DMR5G-SJTSK/DMR5G-SJTSK.xml",
        notes=(
            "Open data. Vstup pro tools/dmr5g_hillshade.py (Fáze 5, F5-1): "
            "LAZ -> DTM -> multi-directional hillshade + SVF -> PMTiles."
        ),
    ),
]

SOURCES_BY_ID: Dict[str, Source] = {s.id: s for s in SOURCES}


def all_sources() -> List[Source]:
    """Vrátí seznam všech registrovaných zdrojů."""
    return list(SOURCES)


def get_source(source_id: str) -> Source:
    """Najde zdroj podle id; vyhodí ``KeyError`` se seznamem platných id."""
    try:
        return SOURCES_BY_ID[source_id]
    except KeyError:
        known = ", ".join(sorted(SOURCES_BY_ID))
        raise KeyError("Neznámý zdroj %r. Známé zdroje: %s" % (source_id, known))


def p1_sources(include_unverified: bool = False) -> List[Source]:
    """Zdroje priority P1 — jejich výpadek shodí ``check_endpoints.py`` (exit 1)."""
    return [
        s
        for s in SOURCES
        if s.priority == "P1" and (include_unverified or s.verified)
    ]


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _format_table(sources: List[Source]) -> str:
    rows = [("ID", "TYP", "CRS", "ZOOM", "PRIO", "NÁZEV")]
    for s in sources:
        rows.append(
            (
                s.id,
                s.type,
                s.crs,
                "%d-%d" % (s.min_zoom, s.max_zoom),
                s.priority + ("" if s.verified else "*"),
                s.title,
            )
        )
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    lines = []
    for idx, row in enumerate(rows):
        lines.append("  ".join(row[i].ljust(widths[i]) for i in range(len(row))).rstrip())
        if idx == 0:
            lines.append("  ".join("-" * widths[i] for i in range(len(row))))
    lines.append("")
    lines.append("* = endpoint není ověřený (PLAN.md: 'ověřit při implementaci')")
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    """Vypíše registr datových zdrojů (tabulka nebo JSON)."""
    parser = argparse.ArgumentParser(
        description="Registr datových zdrojů DetektorMapy (PLAN.md sekce 3).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--json", action="store_true", help="výpis ve formátu JSON")
    parser.add_argument("--id", help="detail jednoho zdroje podle id")
    parser.add_argument("--p1", action="store_true", help="jen zdroje priority P1")
    args = parser.parse_args(argv)

    if args.id:
        try:
            selected = [get_source(args.id)]
        except KeyError as exc:
            print(str(exc), file=sys.stderr)
            return 2
    elif args.p1:
        selected = p1_sources()
    else:
        selected = all_sources()

    if args.json:
        print(json.dumps([s.to_dict() for s in selected], indent=2, ensure_ascii=False))
    elif args.id:
        s = selected[0]
        for key, value in s.to_dict().items():
            print("%-16s %s" % (key + ":", value))
    else:
        print(_format_table(selected))
        print()
        print(LICENCE_WARNING)
    return 0


if __name__ == "__main__":
    sys.exit(main())
