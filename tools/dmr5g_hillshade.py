#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DMR 5G: LAZ -> DTM -> multi-directional hillshade + SVF -> PMTiles (F5-1).

Cíl podle PLAN.md: reliéf výrazně čitelnější než výchozí hillshade z WMS ČÚZK —
úvozy, milíře, valy a zaniklé cesty musí být vidět ostřeji.

Pipeline:
  1. stáhnout dlaždice LAZ z ATOM feedu ČÚZK (open data),
  2. z bodů třídy 2 (terén) postavit DTM (PDAL ``writers.gdal``, jinak ``gdal_grid``),
  3. spočítat **multi-directional hillshade** (4+ azimutů s vahami) a volitelně
     **sky-view factor**, zkombinovat do jednoho šedotónového rastru — vše v numpy,
  4. reprojektovat do EPSG:3857, vydlaždicovat a předat ``build_pmtiles.py``.

Chybějící PDAL/GDAL skript nepřekvapí tracebackem — napíše, co doinstalovat
(``brew install gdal pdal``), a skončí.

Příklady::

    # celý řetězec pro malé území
    python3 tools/dmr5g_hillshade.py --bbox 14.66 49.38 14.74 49.44 \\
        --work data/dmr5g/tabor --pmtiles data/pmtiles/dmr5g_tabor.pmtiles

    # jen hillshade z hotového DTM (bez LAZ a bez PDAL)
    python3 tools/dmr5g_hillshade.py --dtm dtm.tif --work /tmp/hs --no-download
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Any, Dict, List, Optional, Sequence, Tuple
from xml.etree import ElementTree

try:
    import numpy as np
except ImportError:  # pragma: no cover
    np = None  # type: ignore[assignment]

try:
    import requests
except ImportError:  # pragma: no cover
    requests = None  # type: ignore[assignment]

try:
    from sources import LICENCE_WARNING, get_source
except ImportError:  # spuštěno mimo adresář tools/
    from tools.sources import LICENCE_WARNING, get_source  # type: ignore

USER_AGENT = "DetektorMapy-dmr5g/1.0 (osobni pouziti)"
DEFAULT_AZIMUTHS = (315.0, 15.0, 75.0, 135.0, 195.0, 255.0)
ENVI_DTYPE = {"float32": 4, "uint8": 1, "int16": 2}


def missing_tools(names: Sequence[str]) -> List[str]:
    """Které z uvedených nástrojů nejsou v PATH."""
    return [n for n in names if not shutil.which(n)]


def _require(names: Sequence[str], hint: str) -> None:
    missing = missing_tools(names)
    if missing:
        raise RuntimeError(
            "Chybí nástroje: %s\n  Instalace na macOS: %s" % (", ".join(missing), hint)
        )


# ===========================================================================
# 1) ATOM feed ČÚZK
# ===========================================================================

def _localname(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_atom(xml_bytes: bytes) -> Dict[str, List[Dict[str, Any]]]:
    """Rozparsuje ATOM feed na položky se souborem a/nebo na vnořené feedy.

    Vrací ``{"files": [...], "feeds": [...]}``. U položek se pokouší vytáhnout
    ``georss:box`` / ``georss:polygon`` pro filtrování podle bboxu.
    """
    out = {"files": [], "feeds": []}  # type: Dict[str, List[Dict[str, Any]]]
    try:
        root = ElementTree.fromstring(xml_bytes)
    except ElementTree.ParseError as exc:
        raise ValueError("ATOM feed se nepodařilo naparsovat: %s" % exc)

    for entry in root.iter():
        if _localname(entry.tag) != "entry":
            continue
        title = ""
        bbox: Optional[Tuple[float, float, float, float]] = None
        links: List[Tuple[str, str]] = []
        for child in entry:
            name = _localname(child.tag)
            if name == "title" and child.text:
                title = child.text.strip()
            elif name == "link":
                href = child.get("href") or ""
                if href:
                    links.append((href, child.get("type") or ""))
            elif name in ("box", "polygon") and child.text:
                bbox = _georss_to_bbox(name, child.text)
        for href, ctype in links:
            lowered = href.lower()
            item = {"title": title, "url": href, "bbox": bbox, "type": ctype}
            if lowered.endswith((".laz", ".las", ".zip", ".7z")):
                out["files"].append(item)
            elif lowered.endswith(".xml") or "atom" in ctype:
                out["feeds"].append(item)
    return out


def _georss_to_bbox(kind: str, text: str) -> Optional[Tuple[float, float, float, float]]:
    """georss box/polygon (pořadí lat lon) -> (west, south, east, north)."""
    try:
        values = [float(v) for v in text.replace(",", " ").split()]
    except ValueError:
        return None
    if kind == "box" and len(values) >= 4:
        south, west, north, east = values[:4]
        return min(west, east), min(south, north), max(west, east), max(south, north)
    if kind == "polygon" and len(values) >= 6:
        lats = values[0::2]
        lons = values[1::2]
        return min(lons), min(lats), max(lons), max(lats)
    return None


def bbox_intersects(a: Sequence[float], b: Sequence[float]) -> bool:
    """Protínají se dva bboxy (w, s, e, n)?"""
    return not (a[2] < b[0] or b[2] < a[0] or a[3] < b[1] or b[3] < a[1])


def discover_laz(feed_url: str, bbox: Optional[Sequence[float]] = None,
                 name_filter: Optional[str] = None, timeout: float = 30.0,
                 max_depth: int = 2, max_files: int = 400) -> List[Dict[str, Any]]:
    """Projde ATOM feed (i vnořené) a vrátí kandidátní soubory LAZ/ZIP.

    POZN.: struktura feedu ČÚZK se v čase mění a jednotlivé položky nemusí nést
    georss bbox. Když se podle bboxu nedá filtrovat, použij ``--name-filter``
    s označením mapového listu (např. ``TABO``) — jinak by se stahovala celá ČR.
    """
    if requests is None:
        raise RuntimeError("Chybí modul 'requests' (pip install -r tools/requirements.txt)")
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})

    seen = set()
    queue: List[Tuple[str, int]] = [(feed_url, 0)]
    files: List[Dict[str, Any]] = []
    pattern = re.compile(name_filter, re.IGNORECASE) if name_filter else None

    while queue and len(files) < max_files:
        url, depth = queue.pop(0)
        if url in seen:
            continue
        seen.add(url)
        try:
            response = session.get(url, timeout=(10.0, timeout))
            response.raise_for_status()
            parsed = parse_atom(response.content)
        except Exception as exc:  # noqa: BLE001 - jeden feed nesmí shodit běh
            print("  ! feed %s: %s" % (url, str(exc)[:120]), file=sys.stderr)
            continue

        for item in parsed["files"]:
            if pattern and not pattern.search(item["title"] + " " + item["url"]):
                continue
            if bbox and item["bbox"] and not bbox_intersects(bbox, item["bbox"]):
                continue
            files.append(item)
        if depth < max_depth:
            for feed in parsed["feeds"]:
                if bbox and feed["bbox"] and not bbox_intersects(bbox, feed["bbox"]):
                    continue
                if pattern and feed["bbox"] is None and not pattern.search(
                        feed["title"] + " " + feed["url"]):
                    continue
                queue.append((feed["url"], depth + 1))
    return files


def download_files(items: Sequence[Dict[str, Any]], outdir: str, timeout: float = 300.0
                   ) -> List[str]:
    """Stáhne soubory (přeskočí existující) a rozbalí ZIP. Vrací cesty k LAZ/LAS."""
    if requests is None:
        raise RuntimeError("Chybí modul 'requests'")
    os.makedirs(outdir, exist_ok=True)
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT})
    result: List[str] = []
    for item in items:
        url = item["url"]
        filename = os.path.basename(url.split("?")[0]) or "tile.laz"
        path = os.path.join(outdir, filename)
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            print("  stahuji %s" % filename)
            try:
                with session.get(url, timeout=(10.0, timeout), stream=True) as response:
                    response.raise_for_status()
                    tmp = path + ".part"
                    with open(tmp, "wb") as handle:
                        for chunk in response.iter_content(1 << 20):
                            handle.write(chunk)
                    os.replace(tmp, path)
            except Exception as exc:  # noqa: BLE001 - pokračujeme dalším souborem
                print("  ! %s: %s" % (filename, str(exc)[:140]), file=sys.stderr)
                continue
        else:
            print("  mám %s" % filename)
        if path.lower().endswith(".zip"):
            result.extend(_extract_zip(path, outdir))
        elif path.lower().endswith((".laz", ".las")):
            result.append(path)
    return result


def _extract_zip(path: str, outdir: str) -> List[str]:
    import zipfile

    extracted: List[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for member in archive.namelist():
                if not member.lower().endswith((".laz", ".las")):
                    continue
                target = os.path.join(outdir, os.path.basename(member))
                if not os.path.exists(target):
                    with archive.open(member) as src, open(target, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                extracted.append(target)
    except (zipfile.BadZipFile, OSError) as exc:
        print("  ! %s nelze rozbalit: %s" % (path, exc), file=sys.stderr)
    return extracted


# ===========================================================================
# 2) LAZ -> DTM
# ===========================================================================

def build_dtm_pdal(laz_files: Sequence[str], out_tif: str, resolution: float = 1.0,
                   srs: str = "EPSG:5514", dry_run: bool = False,
                   class_limits: str = "") -> None:
    """Postaví DTM pomocí PDAL ``writers.gdal`` (IDW).

    OVĚŘENO NAOSTRO 2026-08-20: DMR 5G od ČÚZK je odvozený, čistě terénní produkt
    a jeho body nesou ``Classification = 8`` (model key-point), NE 2 (ground).
    Dřívější natvrdo zadrátovaný filtr ``Classification[2:2]`` proto vyprázdnil
    každý soubor a PDAL spadl na "no points". Výchozí je tedy žádný filtr;
    ``class_limits`` (např. "Classification[2:2]") dává smysl jen pro surová,
    klasifikovaná lidarová data odjinud.
    """
    _require(["pdal"], "brew install pdal")
    stages: List[Any] = list(laz_files)
    if class_limits:
        stages.append({"type": "filters.range", "limits": class_limits})
    stages.append(
        {
            "type": "writers.gdal",
            "filename": out_tif,
            "resolution": resolution,
            "output_type": "idw",
            "window_size": 4,
            "gdaldriver": "GTiff",
            "nodata": -9999,
            "default_srs": srs,
        },
    )
    pipeline = {"pipeline": stages}
    handle, pipeline_path = tempfile.mkstemp(suffix=".json", prefix="dm_pdal_")
    with os.fdopen(handle, "w", encoding="utf-8") as fh:
        json.dump(pipeline, fh, indent=2)
    cmd = ["pdal", "pipeline", pipeline_path]
    print("  $ " + " ".join(cmd))
    if dry_run:
        print("  (pipeline: %s)" % pipeline_path)
        return
    try:
        subprocess.run(cmd, check=True, timeout=3600)
    finally:
        if not dry_run:
            os.unlink(pipeline_path)


def build_dtm_gdal_grid(xyz_files: Sequence[str], out_tif: str, resolution: float = 1.0,
                        srs: str = "EPSG:5514", dry_run: bool = False) -> None:
    """Fallback bez PDAL: ``gdal_grid`` nad textovým XYZ (LAZ musí být předem rozbalený)."""
    _require(["gdal_grid"], "brew install gdal")
    if not xyz_files:
        raise RuntimeError("gdal_grid potřebuje textové XYZ/CSV vstupy (LAZ neumí číst).")
    cmd = [
        "gdal_grid", "-a", "invdist:power=2.0:smoothing=0.0", "-txe", "-tye",
        "-a_srs", srs, "-tr", str(resolution), str(resolution),
        "-of", "GTiff", xyz_files[0], out_tif,
    ]
    print("  $ " + " ".join(cmd))
    if not dry_run:
        subprocess.run(cmd, check=True, timeout=3600)


# ===========================================================================
# 3) Čtení/zápis rastru bez závislosti na python bindings GDAL
# ===========================================================================

def raster_info(path: str) -> Dict[str, Any]:
    """Vrátí geotransform, rozměry a WKT projekci přes ``gdalinfo -json``."""
    _require(["gdalinfo"], "brew install gdal")
    proc = subprocess.run(["gdalinfo", "-json", path], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=300)
    if proc.returncode != 0:
        raise RuntimeError("gdalinfo selhal: %s" % proc.stderr.decode("utf-8", "replace")[:200])
    info = json.loads(proc.stdout.decode("utf-8", "replace"))
    return {
        "width": int(info["size"][0]),
        "height": int(info["size"][1]),
        "geotransform": info.get("geoTransform"),
        "wkt": (info.get("coordinateSystem") or {}).get("wkt", ""),
        "corner_coordinates": info.get("cornerCoordinates", {}),
    }


def read_raster_array(path: str, band: int = 1):
    """Načte pásmo rastru do numpy float32 (přes dočasný ENVI export)."""
    if np is None:
        raise RuntimeError("Chybí numpy (pip install numpy)")
    _require(["gdal_translate"], "brew install gdal")
    info = raster_info(path)
    workdir = tempfile.mkdtemp(prefix="dm_envi_")
    try:
        raw = os.path.join(workdir, "band.img")
        subprocess.run(
            ["gdal_translate", "-q", "-of", "ENVI", "-ot", "Float32", "-b", str(band), path, raw],
            check=True, timeout=1800, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        array = np.fromfile(raw, dtype="<f4")
        expected = info["width"] * info["height"]
        if array.size < expected:
            raise RuntimeError("ENVI export je kratší, než odpovídá rozměrům rastru")
        array = array[:expected].reshape((info["height"], info["width"]))
        return array.astype("float32"), info
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def write_envi(path: str, array, dtype: str = "uint8") -> None:
    """Zapíše numpy pole jako ENVI (raw + .hdr) — GDAL to čte bez bindings."""
    if np is None:
        raise RuntimeError("Chybí numpy")
    array.astype(dtype).tofile(path)
    height, width = array.shape
    with open(os.path.splitext(path)[0] + ".hdr", "w", encoding="ascii") as handle:
        handle.write(
            "ENVI\nsamples = %d\nlines = %d\nbands = 1\nheader offset = 0\n"
            "file type = ENVI Standard\ndata type = %d\ninterleave = bsq\n"
            "byte order = 0\n" % (width, height, ENVI_DTYPE[dtype])
        )


def envi_to_geotiff(envi_path: str, out_tif: str, info: Dict[str, Any],
                    srs: str = "EPSG:5514") -> None:
    """Z ENVI udělá georeferencovaný GeoTIFF podle geotransformu zdrojového DTM."""
    _require(["gdal_translate"], "brew install gdal")
    gt = info["geotransform"]
    ulx, uly = gt[0], gt[3]
    lrx = gt[0] + gt[1] * info["width"]
    lry = gt[3] + gt[5] * info["height"]
    subprocess.run(
        ["gdal_translate", "-q", "-of", "GTiff", "-a_srs", srs,
         "-a_ullr", "%.6f" % ulx, "%.6f" % uly, "%.6f" % lrx, "%.6f" % lry,
         "-co", "COMPRESS=DEFLATE", "-co", "TILED=YES", envi_path, out_tif],
        check=True, timeout=1800, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )


# ===========================================================================
# 4) Multi-directional hillshade + sky-view factor (čistě numpy)
# ===========================================================================

def _slope_aspect(dtm, cellsize: float, z_factor: float = 1.0):
    """Spočítá sklon a orientaci svahu z pole výšek."""
    dzdy, dzdx = np.gradient(dtm.astype("float64"), cellsize, cellsize)
    dzdx *= z_factor
    dzdy *= z_factor
    slope = np.arctan(np.sqrt(dzdx * dzdx + dzdy * dzdy))
    aspect = np.arctan2(dzdy, -dzdx)
    return slope, aspect


def multidirectional_hillshade(dtm, cellsize: float = 1.0,
                               azimuths: Sequence[float] = DEFAULT_AZIMUTHS,
                               altitude: float = 45.0, z_factor: float = 1.0,
                               weights: Optional[Sequence[float]] = None):
    """Vážený hillshade z několika azimutů — výrazně čitelnější než jeden směr.

    Vrací pole float v rozsahu 0..1. Standardní ČÚZK hillshade používá jediný
    azimut 315°, takže tvary kolmé na světlo zanikají; kombinace 6 směrů je
    pro úvozy a valy podstatně ostřejší.
    """
    if np is None:
        raise RuntimeError("Chybí numpy (pip install numpy)")
    slope, aspect = _slope_aspect(dtm, cellsize, z_factor)
    zenith = math.radians(90.0 - altitude)
    if weights is None:
        weights = [1.0] * len(azimuths)
    if len(weights) != len(azimuths):
        raise ValueError("počet vah neodpovídá počtu azimutů")
    total = float(sum(weights))
    accumulator = np.zeros_like(slope, dtype="float64")
    for azimuth, weight in zip(azimuths, weights):
        az_rad = math.radians(360.0 - azimuth + 90.0)
        shaded = (
            math.cos(zenith) * np.cos(slope)
            + math.sin(zenith) * np.sin(slope) * np.cos(az_rad - aspect)
        )
        accumulator += weight * np.clip(shaded, 0.0, 1.0)
    return accumulator / total


def _shift(array, dy: int, dx: int):
    """Posun pole o (dy, dx) s doplněním okrajů nejbližší hodnotou."""
    out = np.empty_like(array)
    height, width = array.shape
    ys = slice(max(0, dy), height + min(0, dy))
    xs = slice(max(0, dx), width + min(0, dx))
    ys_src = slice(max(0, -dy), height + min(0, -dy))
    xs_src = slice(max(0, -dx), width + min(0, -dx))
    out[:] = array
    out[ys, xs] = array[ys_src, xs_src]
    return out


def sky_view_factor(dtm, cellsize: float = 1.0, directions: int = 16,
                    max_radius: int = 10, radius_step: int = 1):
    """Sky-view factor — podíl viditelné oblohy (0..1). Zvýrazní konkávní tvary.

    Implementace: pro každý směr se hledá maximální horizontální úhel do
    vzdálenosti ``max_radius`` pixelů; SVF = průměr ``1 - sin(horizont)``.
    """
    if np is None:
        raise RuntimeError("Chybí numpy (pip install numpy)")
    base = dtm.astype("float64")
    accumulator = np.zeros_like(base)
    for index in range(directions):
        angle = 2.0 * math.pi * index / directions
        horizon = np.zeros_like(base)
        for radius in range(radius_step, max_radius + 1, radius_step):
            dx = int(round(radius * math.cos(angle)))
            dy = int(round(radius * math.sin(angle)))
            if dx == 0 and dy == 0:
                continue
            distance = math.hypot(dx, dy) * cellsize
            delta = _shift(base, -dy, -dx) - base
            np.maximum(horizon, np.arctan(delta / distance), out=horizon)
        accumulator += 1.0 - np.sin(np.clip(horizon, 0.0, math.pi / 2.0))
    return accumulator / directions


def composite(hillshade, svf=None, svf_weight: float = 0.4, gamma: float = 1.0):
    """Zkombinuje hillshade a SVF do jednoho uint8 rastru (0..255)."""
    if svf is None or svf_weight <= 0:
        blended = hillshade
    else:
        weight = min(max(svf_weight, 0.0), 1.0)
        blended = (1.0 - weight) * hillshade + weight * svf
    blended = np.clip(blended, 0.0, 1.0)
    if gamma and gamma != 1.0:
        blended = np.power(blended, 1.0 / gamma)
    return (blended * 255.0).astype("uint8")


# ===========================================================================
# 5) Navazující kroky (warp + dlaždice + PMTiles)
# ===========================================================================

def warp_to_3857(src_tif: str, dst_tif: str, resample: str = "lanczos",
                 dry_run: bool = False) -> None:
    """Reprojekce do Web Mercatoru (runtime v aplikaci je vždy 3857)."""
    _require(["gdalwarp"], "brew install gdal")
    cmd = ["gdalwarp", "-overwrite", "-t_srs", "EPSG:3857", "-r", resample,
           "-co", "COMPRESS=DEFLATE", "-co", "TILED=YES", src_tif, dst_tif]
    print("  $ " + " ".join(cmd))
    if not dry_run:
        subprocess.run(cmd, check=True, timeout=3600)


def make_tiles(src_tif: str, tiles_dir: str, zoom: str = "12-17",
               resample: str = "lanczos", dry_run: bool = False) -> None:
    """XYZ dlaždice přes ``gdal2tiles.py``."""
    _require(["gdal2tiles.py"], "brew install gdal")
    cmd = ["gdal2tiles.py", "--xyz", "--profile", "mercator", "--zoom", zoom,
           "--resampling", resample, "--processes", "4", "--webviewer", "none",
           src_tif, tiles_dir]
    print("  $ " + " ".join(cmd))
    if not dry_run:
        subprocess.run(cmd, check=True, timeout=7200)


def make_pmtiles(tiles_dir: str, out_path: str, name: str, attribution: str,
                 dry_run: bool = False) -> None:
    """Předá dlaždice do ``tools/build_pmtiles.py``."""
    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_pmtiles.py")
    cmd = [sys.executable, script, "--tiles", tiles_dir, "--out", out_path,
           "--name", name, "--attribution", attribution]
    print("  $ " + " ".join(cmd))
    if not dry_run:
        subprocess.run(cmd, check=True, timeout=7200)


def main(argv: Optional[List[str]] = None) -> int:
    """Postaví z DMR 5G čitelný stínovaný reliéf a uloží ho jako PMTiles."""
    parser = argparse.ArgumentParser(
        description="DMR 5G LAZ -> DTM -> multi-directional hillshade + SVF -> PMTiles (F5-1).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--bbox", nargs=4, type=float, metavar=("W", "S", "E", "N"),
                        help="zájmové území ve WGS84")
    parser.add_argument("--work", required=True, help="pracovní adresář (LAZ, DTM, dlaždice)")
    parser.add_argument("--atom", help="URL ATOM feedu (výchozí: zdroj dmr5g_atom ze sources.py)")
    parser.add_argument("--name-filter", help="regulární výraz na název mapového listu (např. TABO)")
    parser.add_argument("--max-files", type=int, default=40, help="strop na počet stahovaných LAZ")
    parser.add_argument("--no-download", action="store_true", help="přeskočit stahování LAZ")
    parser.add_argument("--dtm", help="použít existující DTM GeoTIFF místo stavby z LAZ")
    parser.add_argument("--resolution", type=float, default=1.0, help="rozlišení DTM v metrech")
    parser.add_argument("--dtm-srs", default="EPSG:5514", help="CRS vstupních LAZ / DTM")
    # hillshade
    parser.add_argument("--azimuths", default=",".join(str(a) for a in DEFAULT_AZIMUTHS),
                        help="azimuty osvětlení ve stupních, oddělené čárkou")
    parser.add_argument("--weights", help="váhy azimutů oddělené čárkou (výchozí: rovnoměrné)")
    parser.add_argument("--altitude", type=float, default=45.0, help="výška slunce ve stupních")
    parser.add_argument("--z-factor", type=float, default=1.0, help="vertikální převýšení")
    parser.add_argument("--svf", action="store_true", help="přimíchat sky-view factor")
    parser.add_argument("--svf-weight", type=float, default=0.4, help="váha SVF v kompozitu")
    parser.add_argument("--svf-directions", type=int, default=16, help="počet směrů pro SVF")
    parser.add_argument("--svf-radius", type=int, default=10, help="dosah SVF v pixelech")
    parser.add_argument("--gamma", type=float, default=1.0, help="gamma korekce výsledku")
    # výstup
    parser.add_argument("--zoom", default="12-17", help="rozsah zoomů pro dlaždice")
    parser.add_argument("--pmtiles", help="výstupní .pmtiles (bez něj se skončí u dlaždic)")
    parser.add_argument(
        "--class-filter", default="",
        help="PDAL filters.range limity, např. 'Classification[2:2]' pro surová "
             "klasifikovaná data; DMR 5G má vše ve třídě 8, výchozí je bez filtru",
    )
    parser.add_argument("--dry-run", action="store_true", help="jen vypsat kroky a příkazy")
    args = parser.parse_args(argv)

    if np is None and not args.dry_run:
        print("CHYBA: chybí numpy. Nainstaluj: python3 -m pip install numpy", file=sys.stderr)
        return 2

    hard_missing = missing_tools(["gdalinfo", "gdal_translate", "gdalwarp", "gdal2tiles.py"])
    if hard_missing and not args.dry_run:
        print("CHYBA: v PATH chybí %s.\n       Instalace na macOS: brew install gdal pdal"
              % ", ".join(hard_missing), file=sys.stderr)
        return 3
    if hard_missing:
        print("POZOR: v PATH chybí %s — --dry-run jen vypíše kroky."
              % ", ".join(hard_missing), file=sys.stderr)

    os.makedirs(args.work, exist_ok=True)
    laz_dir = os.path.join(args.work, "laz")
    dtm_path = args.dtm or os.path.join(args.work, "dtm.tif")
    tiles_dir = os.path.join(args.work, "tiles")

    try:
        azimuths = [float(v) for v in args.azimuths.split(",") if v.strip()]
        weights = [float(v) for v in args.weights.split(",")] if args.weights else None
    except ValueError:
        print("CHYBA: --azimuths/--weights musí být čísla oddělená čárkou", file=sys.stderr)
        return 2

    # --- 1) LAZ ----------------------------------------------------------
    if not args.dtm and not args.no_download:
        feed = args.atom or get_source("dmr5g_atom").effective_url
        print("1) hledám LAZ dlaždice v ATOM feedu\n   %s" % feed)
        if not args.bbox and not args.name_filter:
            print("CHYBA: bez --bbox nebo --name-filter by se stahovala celá ČR (stovky GB).",
                  file=sys.stderr)
            return 2
        if args.dry_run:
            print("   (--dry-run: feed se nestahuje)")
            candidates: List[Dict[str, Any]] = []
        else:
            try:
                candidates = discover_laz(feed, args.bbox, args.name_filter,
                                          max_files=args.max_files)
            except RuntimeError as exc:
                print("CHYBA: %s" % exc, file=sys.stderr)
                return 3
            print("   nalezeno %d souborů" % len(candidates))
            if not candidates:
                print("   TIP: feed ČÚZK nemusí u položek uvádět georss bbox. "
                      "Zkus --name-filter s označením mapového listu.", file=sys.stderr)
                return 1
            laz_files = download_files(candidates[: args.max_files], laz_dir)
            if not laz_files:
                print("CHYBA: nestáhl se žádný LAZ", file=sys.stderr)
                return 1
            print("\n2) stavím DTM (PDAL, rozlišení %.2f m)" % args.resolution)
            try:
                build_dtm_pdal(laz_files, dtm_path, args.resolution, args.dtm_srs,
                               class_limits=args.class_filter)
            except (RuntimeError, subprocess.CalledProcessError) as exc:
                print("CHYBA: stavba DTM selhala: %s" % exc, file=sys.stderr)
                print("       (PDAL nainstaluješ přes: brew install pdal)", file=sys.stderr)
                return 3
    elif args.dtm:
        print("1-2) používám hotový DTM: %s" % dtm_path)
    else:
        print("1-2) stahování přeskočeno (--no-download), čekám DTM v %s" % dtm_path)

    if args.dry_run:
        print("\n3) hillshade: azimuty %s, altitude %.0f°%s"
              % (azimuths, args.altitude, ", + SVF" if args.svf else ""))
        print("4) warp do EPSG:3857, dlaždice %s, PMTiles %s"
              % (args.zoom, args.pmtiles or "(vynecháno)"))
        print("\n--dry-run: nic se nepočítalo.")
        return 0

    if not os.path.exists(dtm_path):
        print("CHYBA: DTM %s neexistuje" % dtm_path, file=sys.stderr)
        return 1

    # --- 3) hillshade ----------------------------------------------------
    print("\n3) multi-directional hillshade (%d azimutů)%s"
          % (len(azimuths), " + sky-view factor" if args.svf else ""))
    try:
        dtm, info = read_raster_array(dtm_path)
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print("CHYBA: nelze načíst DTM: %s" % exc, file=sys.stderr)
        return 3
    nodata_mask = dtm <= -9000
    if nodata_mask.any():
        dtm = np.where(nodata_mask, np.nanmedian(dtm[~nodata_mask]) if (~nodata_mask).any() else 0.0, dtm)
    cellsize = abs(info["geotransform"][1]) if info["geotransform"] else args.resolution

    hillshade = multidirectional_hillshade(
        dtm, cellsize, azimuths, args.altitude, args.z_factor, weights)
    svf = None
    if args.svf:
        svf = sky_view_factor(dtm, cellsize, args.svf_directions, args.svf_radius)
    image = composite(hillshade, svf, args.svf_weight, args.gamma)
    image[nodata_mask] = 0

    relief_envi = os.path.join(args.work, "relief.img")
    relief_tif = os.path.join(args.work, "relief_5514.tif")
    relief_3857 = os.path.join(args.work, "relief_3857.tif")
    write_envi(relief_envi, image, "uint8")
    envi_to_geotiff(relief_envi, relief_tif, info, args.dtm_srs)
    print("   reliéf: %s (%dx%d px, %.2f m/px)"
          % (relief_tif, info["width"], info["height"], cellsize))

    # --- 4) warp + dlaždice + PMTiles ------------------------------------
    print("\n4) reprojekce a dlaždice")
    try:
        warp_to_3857(relief_tif, relief_3857)
        make_tiles(relief_3857, tiles_dir, args.zoom)
        if args.pmtiles:
            make_pmtiles(tiles_dir, args.pmtiles, "DMR 5G reliéf (vlastní render)", "© ČÚZK")
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print("CHYBA: navazující GDAL krok selhal: %s" % exc, file=sys.stderr)
        return 3

    print("\nHotovo. Dlaždice: %s" % tiles_dir)
    if args.pmtiles:
        print("PMTiles: %s" % args.pmtiles)
    print()
    print(LICENCE_WARNING)
    return 0


if __name__ == "__main__":
    sys.exit(main())
