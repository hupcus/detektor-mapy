#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Georeference naskenované mapy z GCP souboru (F3-4).

Obálka nad ``gdal_translate -gcp …`` + ``gdalwarp -tps -r lanczos -t_srs EPSG:3857``.
Vstupem je GCP soubor ve formátu, který exportuje Android aplikace (GCP editor,
režim B — PLAN.md sekce 6)::

    {
      "image": "scan.jpg",
      "width": 5000,
      "height": 4000,
      "gcps": [
        {"px": 123.0, "py": 456.0, "lon": 14.66, "lat": 49.41},
        …
      ],
      "created": "2026-08-19T21:00:00Z"
    }

``px``/``py`` jsou pixelové souřadnice ve skenu (počátek vlevo nahoře, py roste dolů),
``lon``/``lat`` jsou WGS84 (EPSG:4326).

Volitelně naváže na ``gdal2tiles.py`` a ``tools/build_pmtiles.py`` a vyrobí
rovnou hotovou PMTiles vrstvu.

Příklad::

    python3 tools/warp_scan.py --gcp scan_gcp.json --out warped/scan_3857.tif \\
        --tps --tiles --zoom 12-17 --pmtiles data/pmtiles/cisarsky_otisk.pmtiles
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Dict, List, Optional, Sequence, Tuple

GDAL_TOOLS = ("gdal_translate", "gdalwarp")
GDAL_MISSING_MSG = (
    "CHYBA: GDAL není v PATH (chybí %s).\n"
    "       Instalace na macOS:  brew install gdal\n"
    "       Bez GDAL se warp skenu udělat nedá — použij --dry-run a příkazy si "
    "pusť tam, kde GDAL je."
)


class GcpFile(object):
    """Naparsovaný GCP soubor z aplikace."""

    def __init__(self, image: str, width: int, height: int,
                 gcps: List[Dict[str, float]], created: str = "",
                 source_path: Optional[str] = None) -> None:
        self.image = image
        self.width = width
        self.height = height
        self.gcps = gcps
        self.created = created
        self.source_path = source_path

    @property
    def count(self) -> int:
        return len(self.gcps)

    def image_path(self) -> str:
        """Absolutní cesta ke skenu — relativní cesty se berou vůči GCP souboru."""
        if os.path.isabs(self.image):
            return self.image
        base = os.path.dirname(os.path.abspath(self.source_path)) if self.source_path else os.getcwd()
        return os.path.normpath(os.path.join(base, self.image))

    def recommended_method(self) -> str:
        """Doporučí transformaci podle počtu bodů (viz PLAN.md sekce 6)."""
        if self.count >= 6:
            return "tps"
        if self.count >= 3:
            return "polynomial"
        return "nedostatek bodů"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "image": self.image, "width": self.width, "height": self.height,
            "gcps": self.gcps, "created": self.created,
        }


def parse_gcp_json(data: Dict[str, Any], source_path: Optional[str] = None) -> GcpFile:
    """Zvaliduje a naparsuje GCP JSON. Vyhodí ``ValueError`` s českou hláškou."""
    if not isinstance(data, dict):
        raise ValueError("GCP soubor musí být JSON objekt")
    for key in ("image", "width", "height", "gcps"):
        if key not in data:
            raise ValueError("v GCP souboru chybí klíč %r" % key)
    try:
        width = int(data["width"])
        height = int(data["height"])
    except (TypeError, ValueError):
        raise ValueError("width/height musí být celá čísla")
    if width <= 0 or height <= 0:
        raise ValueError("width/height musí být kladné (dostal jsem %sx%s)" % (width, height))
    raw_gcps = data["gcps"]
    if not isinstance(raw_gcps, list) or not raw_gcps:
        raise ValueError("seznam 'gcps' je prázdný nebo není pole")

    gcps: List[Dict[str, float]] = []
    for index, item in enumerate(raw_gcps):
        if not isinstance(item, dict):
            raise ValueError("gcps[%d] není objekt" % index)
        for key in ("px", "py", "lon", "lat"):
            if key not in item:
                raise ValueError("gcps[%d]: chybí %r" % (index, key))
        try:
            px = float(item["px"])
            py = float(item["py"])
            lon = float(item["lon"])
            lat = float(item["lat"])
        except (TypeError, ValueError):
            raise ValueError("gcps[%d]: px/py/lon/lat musí být čísla" % index)
        if not -180.0 <= lon <= 180.0:
            raise ValueError("gcps[%d]: lon %r je mimo -180..180" % (index, lon))
        if not -90.0 <= lat <= 90.0:
            raise ValueError("gcps[%d]: lat %r je mimo -90..90" % (index, lat))
        if not (0.0 <= px <= width and 0.0 <= py <= height):
            raise ValueError(
                "gcps[%d]: pixel (%.1f, %.1f) je mimo obrázek %dx%d" % (index, px, py, width, height)
            )
        gcps.append({"px": px, "py": py, "lon": lon, "lat": lat})

    if len(gcps) < 3:
        raise ValueError(
            "potřebuji aspoň 3 GCP body pro afinní transformaci (mám %d); "
            "pro TPS doporučeno 6+" % len(gcps)
        )
    return GcpFile(
        image=str(data["image"]), width=width, height=height, gcps=gcps,
        created=str(data.get("created", "")), source_path=source_path,
    )


def load_gcp_file(path: str) -> GcpFile:
    """Načte GCP JSON ze souboru."""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except OSError as exc:
        raise ValueError("nelze otevřít GCP soubor %s: %s" % (path, exc))
    except ValueError as exc:
        raise ValueError("GCP soubor %s není platný JSON: %s" % (path, exc))
    return parse_gcp_json(data, source_path=path)


def missing_gdal_tools(extra: Sequence[str] = ()) -> List[str]:
    """Vrátí seznam chybějících GDAL nástrojů v PATH."""
    return [tool for tool in list(GDAL_TOOLS) + list(extra) if not shutil.which(tool)]


def build_translate_cmd(gcp: GcpFile, src: str, dst: str,
                        gcp_srs: str = "EPSG:4326") -> List[str]:
    """Sestaví ``gdal_translate`` příkaz, který do kopie skenu vloží GCP body."""
    cmd = ["gdal_translate", "-of", "GTiff", "-a_srs", gcp_srs]
    for point in gcp.gcps:
        cmd += ["-gcp", "%.6f" % point["px"], "%.6f" % point["py"],
                "%.9f" % point["lon"], "%.9f" % point["lat"]]
    cmd += [src, dst]
    return cmd


def build_warp_cmd(src: str, dst: str, method: str = "tps", order: int = 1,
                   resample: str = "lanczos", target_srs: str = "EPSG:3857",
                   nodata: Optional[str] = None, resolution: Optional[float] = None,
                   compress: str = "DEFLATE") -> List[str]:
    """Sestaví ``gdalwarp`` příkaz (TPS nebo polynom daného řádu)."""
    cmd = ["gdalwarp", "-overwrite", "-t_srs", target_srs, "-r", resample]
    if method == "tps":
        cmd.append("-tps")
    else:
        cmd += ["-order", str(order)]
    cmd += ["-co", "COMPRESS=%s" % compress, "-co", "TILED=YES"]
    if resolution:
        cmd += ["-tr", "%.6f" % resolution, "%.6f" % resolution]
    if nodata is not None:
        cmd += ["-dstnodata", nodata]
    else:
        cmd += ["-dstalpha"]
    cmd += [src, dst]
    return cmd


def build_gdal2tiles_cmd(src: str, out_dir: str, zoom: str,
                         profile: str = "mercator", resample: str = "lanczos",
                         processes: int = 4) -> List[str]:
    """Sestaví ``gdal2tiles.py`` příkaz produkující XYZ dlaždice."""
    return [
        "gdal2tiles.py", "--xyz", "--profile", profile, "--zoom", zoom,
        "--resampling", resample, "--processes", str(processes),
        "--webviewer", "none", src, out_dir,
    ]


def run(cmd: Sequence[str], dry_run: bool = False, timeout: Optional[float] = None) -> int:
    """Spustí příkaz (nebo ho jen vypíše při ``--dry-run``)."""
    printable = " ".join(_quote(part) for part in cmd)
    if dry_run:
        print("  $ " + printable)
        return 0
    print("  $ " + printable, flush=True)
    try:
        proc = subprocess.run(list(cmd), timeout=timeout)
    except FileNotFoundError:
        print("CHYBA: nástroj %r není v PATH" % cmd[0], file=sys.stderr)
        return 127
    except subprocess.TimeoutExpired:
        print("CHYBA: %r překročil timeout" % cmd[0], file=sys.stderr)
        return 124
    return proc.returncode


def _quote(value: str) -> str:
    return "'%s'" % value if (" " in value or not value) else value


def main(argv: Optional[List[str]] = None) -> int:
    """Zgeoreferencuje sken podle GCP souboru a volitelně vyrobí PMTiles."""
    parser = argparse.ArgumentParser(
        description="Warp naskenované mapy podle GCP z Android aplikace (F3-4).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--gcp", required=True, help="cesta ke GCP JSON souboru z aplikace")
    parser.add_argument("--image", help="přepsat cestu ke skenu (jinak podle klíče 'image')")
    parser.add_argument("--out", required=True, help="výstupní georeferencovaný GeoTIFF (EPSG:3857)")
    method = parser.add_mutually_exclusive_group()
    method.add_argument("--tps", action="store_true", help="thin plate spline (doporučeno pro 6+ bodů)")
    method.add_argument("--polynomial", action="store_true", help="polynomiální transformace")
    parser.add_argument("--order", type=int, default=1, choices=(1, 2, 3),
                        help="řád polynomu pro --polynomial")
    parser.add_argument("--resample", default="lanczos", help="metoda převzorkování")
    parser.add_argument("--t-srs", dest="target_srs", default="EPSG:3857", help="cílový CRS")
    parser.add_argument("--gcp-srs", default="EPSG:4326", help="CRS souřadnic v GCP souboru")
    parser.add_argument("--resolution", type=float, help="cílové rozlišení v metrech/pixel")
    parser.add_argument("--nodata", help="hodnota nodata místo alfa kanálu")
    parser.add_argument("--keep-temp", action="store_true", help="nechat mezivýsledky na disku")
    parser.add_argument("--dry-run", action="store_true", help="jen vypsat příkazy, nic nespouštět")
    # navazující kroky
    parser.add_argument("--tiles", action="store_true", help="navázat gdal2tiles.py a vyrobit dlaždice")
    parser.add_argument("--tiles-dir", help="adresář pro dlaždice (výchozí: <out>_tiles)")
    parser.add_argument("--zoom", default="12-17", help="rozsah zoomů pro gdal2tiles")
    parser.add_argument("--pmtiles", help="navázat build_pmtiles.py a zapsat tento .pmtiles soubor")
    parser.add_argument("--name", default="", help="název vrstvy do metadat PMTiles")
    parser.add_argument("--attribution", default="© ČÚZK / Archiv ÚAZK",
                        help="atribuce vrstvy do metadat PMTiles")
    args = parser.parse_args(argv)

    try:
        gcp = load_gcp_file(args.gcp)
    except ValueError as exc:
        print("CHYBA: %s" % exc, file=sys.stderr)
        return 2

    src_image = args.image or gcp.image_path()
    if not args.dry_run and not os.path.exists(src_image):
        print("CHYBA: sken %s neexistuje (uprav klíč 'image' nebo použij --image)" % src_image,
              file=sys.stderr)
        return 2

    if args.tps:
        method_name = "tps"
    elif args.polynomial:
        method_name = "polynomial"
    else:
        method_name = gcp.recommended_method()
        if method_name == "nedostatek bodů":
            print("CHYBA: potřebuji aspoň 3 GCP body", file=sys.stderr)
            return 2
        print("Metoda nezvolena -> podle počtu bodů volím: %s" % method_name)

    if method_name == "tps" and gcp.count < 6:
        print("POZOR: TPS s %d body bývá nestabilní; PLAN.md doporučuje 6+ (jinak --polynomial)."
              % gcp.count, file=sys.stderr)

    print("GCP soubor: %s" % args.gcp)
    print("Sken:       %s (%dx%d px)" % (src_image, gcp.width, gcp.height))
    print("Bodů:       %d, metoda: %s%s"
          % (gcp.count, method_name, "" if method_name == "tps" else " (řád %d)" % args.order))
    print("Vytvořeno:  %s" % (gcp.created or "-"))

    extra_tools = []
    if args.tiles:
        extra_tools.append("gdal2tiles.py")
    missing = missing_gdal_tools(extra_tools)
    if missing and not args.dry_run:
        print(GDAL_MISSING_MSG % ", ".join(missing), file=sys.stderr)
        return 3
    if missing and args.dry_run:
        print("POZOR: v PATH chybí %s — příkazy níže se jen vypisují."
              % ", ".join(missing), file=sys.stderr)

    workdir = tempfile.mkdtemp(prefix="dm_warp_")
    gcp_tif = os.path.join(workdir, "with_gcps.tif")
    exit_code = 0
    try:
        print("\n1) vložení GCP bodů do kopie skenu")
        code = run(build_translate_cmd(gcp, src_image, gcp_tif, args.gcp_srs), args.dry_run)
        if code != 0:
            print("CHYBA: gdal_translate skončil s kódem %d" % code, file=sys.stderr)
            return code

        print("\n2) warp do %s" % args.target_srs)
        out_dir = os.path.dirname(os.path.abspath(args.out))
        if out_dir and not args.dry_run:
            os.makedirs(out_dir, exist_ok=True)
        code = run(
            build_warp_cmd(gcp_tif, args.out, method=method_name, order=args.order,
                           resample=args.resample, target_srs=args.target_srs,
                           nodata=args.nodata, resolution=args.resolution),
            args.dry_run,
        )
        if code != 0:
            print("CHYBA: gdalwarp skončil s kódem %d" % code, file=sys.stderr)
            return code
        if not args.dry_run:
            print("Hotovo: %s (%.1f MB)" % (args.out, os.path.getsize(args.out) / 1048576.0))

        tiles_dir = args.tiles_dir or (os.path.splitext(args.out)[0] + "_tiles")
        if args.tiles:
            print("\n3) dlaždicování (gdal2tiles.py, zoom %s)" % args.zoom)
            code = run(build_gdal2tiles_cmd(args.out, tiles_dir, args.zoom,
                                            resample=args.resample), args.dry_run)
            if code != 0:
                print("CHYBA: gdal2tiles.py skončil s kódem %d" % code, file=sys.stderr)
                return code

        if args.pmtiles:
            print("\n4) PMTiles")
            build_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_pmtiles.py")
            cmd = [sys.executable, build_script, "--tiles", tiles_dir, "--out", args.pmtiles]
            if args.name:
                cmd += ["--name", args.name]
            if args.attribution:
                cmd += ["--attribution", args.attribution]
            code = run(cmd, args.dry_run)
            if code != 0:
                print("CHYBA: build_pmtiles.py skončil s kódem %d" % code, file=sys.stderr)
                return code
    finally:
        if args.keep_temp:
            print("\nMezivýsledky ponechány v %s" % workdir)
        else:
            shutil.rmtree(workdir, ignore_errors=True)

    if args.dry_run:
        print("\n--dry-run: nic se nespouštělo.")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
