#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stažení dlaždic pro bbox + rozsah zoomů do adresáře ``{z}/{x}/{y}.{ext}`` (F1-4).

Podporuje:
  * **WMTS** — RESTful šablona i KVP ``GetTile`` (přímé dlaždice, bez překreslování).
  * **WMS** — každá webmercator dlaždice se vyrenderuje přes ``GetMap`` s
    ``CRS=EPSG:3857`` a bboxem dlaždice. Když služba 3857 neumí a jede jen v
    ``EPSG:5514``, použije se ``--wms-crs EPSG:5514``: dlaždice se vyžádá
    v 5514 a reprojektuje se přes GDAL (``gdal_translate`` + ``gdalwarp -r lanczos``).
  * **ArcGIS MapServer export** — ``--mode arcgis-export`` (užitečné pro služby,
    které mají dlaždicovou cache jen v S-JTSK).

Vlastnosti: thread pool, slušné rate limiting, retry s exponenciálním backoffem,
resume (existující soubory se přeskočí), progress bar, ``--dry-run``.

Příklady::

    # okres Tábor, II. vojenské mapování, zoom 12-16
    python3 tools/fetch_tiles.py --source ii_vm --bbox 14.45 49.20 15.10 49.60 \\
        --zoom 12-16 --out data/tiles/ii_vm_tabor

    # kolik to bude dlaždic a přibližně MB?
    python3 tools/fetch_tiles.py --source ortofoto --bbox 14.45 49.20 15.10 49.60 \\
        --zoom 12-17 --out /tmp/x --dry-run
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Callable, Dict, Iterator, List, Optional, Sequence, Tuple

try:
    import requests
except ImportError:  # pragma: no cover
    requests = None  # type: ignore[assignment]

try:
    from tqdm import tqdm
except ImportError:  # pragma: no cover
    tqdm = None  # type: ignore[assignment]

try:
    from sources import LICENCE_WARNING, Source, get_source
except ImportError:  # spuštěno mimo adresář tools/
    from tools.sources import LICENCE_WARNING, Source, get_source  # type: ignore

USER_AGENT = "DetektorMapy-fetch_tiles/1.0 (osobni pouziti)"
TILE_SIZE = 256
EARTH_RADIUS = 6378137.0
ORIGIN_SHIFT = math.pi * EARTH_RADIUS  # 20037508.342789244

# WMS 1.3.0 vyžaduje u některých CRS pořadí os lat/lon. Krovak East North (5514)
# má pořadí East, North -> neprohazuje se; klasický Krovak (2065/5513) ano.
AXIS_SWAPPED_WMS_130 = {"EPSG:4326", "EPSG:2065", "EPSG:5513"}


# ===========================================================================
# Tile math (čistá matematika, žádné závislosti — testováno v test_tools.py)
# ===========================================================================

def clamp_lat(lat: float) -> float:
    """Ořeže zeměpisnou šířku na rozsah, který Web Mercator umí (±85.0511°)."""
    return max(-85.05112877980659, min(85.05112877980659, lat))


def lonlat_to_tile_float(lon: float, lat: float, zoom: int) -> Tuple[float, float]:
    """WGS84 -> desetinné souřadnice dlaždice v XYZ schématu."""
    lat = clamp_lat(lat)
    n = 2.0 ** zoom
    x = (lon + 180.0) / 360.0 * n
    lat_rad = math.radians(lat)
    y = (1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n
    return x, y


def lonlat_to_tile(lon: float, lat: float, zoom: int) -> Tuple[int, int]:
    """WGS84 -> celočíselné (x, y) dlaždice na daném zoomu."""
    fx, fy = lonlat_to_tile_float(lon, lat, zoom)
    n = int(2 ** zoom)
    x = min(n - 1, max(0, int(math.floor(fx))))
    y = min(n - 1, max(0, int(math.floor(fy))))
    return x, y


def tile_to_lonlat(x: float, y: float, zoom: int) -> Tuple[float, float]:
    """Souřadnice dlaždice -> WGS84 (severozápadní roh)."""
    n = 2.0 ** zoom
    lon = x / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * y / n))))
    return lon, lat


def tile_bounds_wgs84(x: int, y: int, zoom: int) -> Tuple[float, float, float, float]:
    """Rozsah dlaždice ve WGS84 jako (west, south, east, north)."""
    west, north = tile_to_lonlat(x, y, zoom)
    east, south = tile_to_lonlat(x + 1, y + 1, zoom)
    return west, south, east, north


def lonlat_to_meters(lon: float, lat: float) -> Tuple[float, float]:
    """WGS84 -> EPSG:3857 (metry)."""
    lat = clamp_lat(lat)
    mx = lon * ORIGIN_SHIFT / 180.0
    my = math.log(math.tan((90.0 + lat) * math.pi / 360.0)) * EARTH_RADIUS
    return mx, my


def meters_to_lonlat(mx: float, my: float) -> Tuple[float, float]:
    """EPSG:3857 (metry) -> WGS84."""
    lon = mx / ORIGIN_SHIFT * 180.0
    lat = math.degrees(2.0 * math.atan(math.exp(my / EARTH_RADIUS)) - math.pi / 2.0)
    return lon, lat


def tile_bounds_3857(x: int, y: int, zoom: int) -> Tuple[float, float, float, float]:
    """Rozsah dlaždice v EPSG:3857 jako (minx, miny, maxx, maxy)."""
    resolution = (2.0 * ORIGIN_SHIFT) / (2.0 ** zoom)
    minx = -ORIGIN_SHIFT + x * resolution
    maxx = minx + resolution
    maxy = ORIGIN_SHIFT - y * resolution
    miny = maxy - resolution
    return minx, miny, maxx, maxy


def tile_range(bbox: Sequence[float], zoom: int) -> Tuple[int, int, int, int]:
    """bbox (w, s, e, n) -> (xmin, ymin, xmax, ymax) včetně obou konců."""
    west, south, east, north = bbox
    x0, y0 = lonlat_to_tile(west, north, zoom)
    x1, y1 = lonlat_to_tile(east, south, zoom)
    # Dlaždice, která končí přesně na hranici bboxu, se nestahuje zbytečně.
    fx1, fy1 = lonlat_to_tile_float(east, south, zoom)
    if abs(fx1 - round(fx1)) < 1e-9 and x1 > x0:
        x1 -= 1
    if abs(fy1 - round(fy1)) < 1e-9 and y1 > y0:
        y1 -= 1
    return min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)


def tiles_in_bbox(bbox: Sequence[float], zoom: int) -> Iterator[Tuple[int, int, int]]:
    """Generátor (z, x, y) pro všechny dlaždice protínající bbox."""
    xmin, ymin, xmax, ymax = tile_range(bbox, zoom)
    for x in range(xmin, xmax + 1):
        for y in range(ymin, ymax + 1):
            yield zoom, x, y


def count_tiles(bbox: Sequence[float], zooms: Sequence[int]) -> int:
    """Kolik dlaždic celkem pokrývá bbox na daných zoomech."""
    total = 0
    for zoom in zooms:
        xmin, ymin, xmax, ymax = tile_range(bbox, zoom)
        total += (xmax - xmin + 1) * (ymax - ymin + 1)
    return total


def parse_zoom_range(text: str) -> List[int]:
    """Naparsuje '12-16', '14' nebo '10,12,14' na seznam zoomů."""
    zooms: List[int] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            lo_s, hi_s = part.split("-", 1)
            lo, hi = int(lo_s), int(hi_s)
            if lo > hi:
                lo, hi = hi, lo
            zooms.extend(range(lo, hi + 1))
        else:
            zooms.append(int(part))
    if not zooms:
        raise ValueError("prázdný rozsah zoomů")
    for zoom in zooms:
        if not 0 <= zoom <= 24:
            raise ValueError("zoom mimo rozsah 0-24: %d" % zoom)
    return sorted(set(zooms))


# ===========================================================================
# Stavba URL
# ===========================================================================

def wmts_rest_url(source: Source, z: int, x: int, y: int, template: Optional[str] = None) -> str:
    """RESTful WMTS / XYZ šablona: dosadí {z} {x} {y} {layer} {tilematrixset} {style}."""
    tmpl = template or source.tile_template
    if not tmpl:
        raise ValueError(
            "Zdroj %r nemá tile_template; použij --tile-template nebo --kvp." % source.id
        )
    return (
        tmpl.replace("{z}", str(z))
        .replace("{x}", str(x))
        .replace("{y}", str(y))
        .replace("{layer}", source.layer)
        .replace("{tilematrixset}", source.tilematrixset or "GoogleMapsCompatible")
        .replace("{style}", "default")
    )


def wmts_kvp_url(source: Source, z: int, x: int, y: int, tilematrixset: Optional[str] = None,
                 tilematrix: Optional[str] = None) -> str:
    """KVP varianta WMTS GetTile (TILEMATRIX = z, TILEROW = y, TILECOL = x)."""
    base = source.effective_url
    sep = "&" if "?" in base else "?"
    matrixset = tilematrixset or source.tilematrixset or "GoogleMapsCompatible"
    matrix = tilematrix if tilematrix is not None else str(z)
    params = [
        ("SERVICE", "WMTS"),
        ("REQUEST", "GetTile"),
        ("VERSION", "1.0.0"),
        ("LAYER", source.layer),
        ("STYLE", "default"),
        ("TILEMATRIXSET", matrixset),
        ("TILEMATRIX", matrix),
        ("TILEROW", str(y)),
        ("TILECOL", str(x)),
        ("FORMAT", source.fmt),
    ]
    return base + sep + "&".join("%s=%s" % kv for kv in params)


def _wms_bbox_string(crs: str, version: str, bbox: Sequence[float]) -> str:
    minx, miny, maxx, maxy = bbox
    if version >= "1.3.0" and crs.upper() in AXIS_SWAPPED_WMS_130:
        return "%.9f,%.9f,%.9f,%.9f" % (miny, minx, maxy, maxx)
    return "%.9f,%.9f,%.9f,%.9f" % (minx, miny, maxx, maxy)


def wms_getmap_url(source: Source, bbox: Sequence[float], crs: str = "EPSG:3857",
                   width: int = TILE_SIZE, height: int = TILE_SIZE,
                   version: str = "1.3.0", styles: str = "",
                   transparent: bool = False) -> str:
    """Sestaví WMS GetMap pro konkrétní bbox (v jednotkách daného CRS)."""
    base = source.wms_url or source.effective_url
    sep = "&" if "?" in base else "?"
    crs_key = "CRS" if version >= "1.3.0" else "SRS"
    params = [
        ("SERVICE", "WMS"),
        ("REQUEST", "GetMap"),
        ("VERSION", version),
        ("LAYERS", source.layer),
        ("STYLES", styles),
        (crs_key, crs),
        ("BBOX", _wms_bbox_string(crs, version, bbox)),
        ("WIDTH", str(width)),
        ("HEIGHT", str(height)),
        ("FORMAT", source.fmt),
        ("TRANSPARENT", "TRUE" if transparent else "FALSE"),
    ]
    return base + sep + "&".join("%s=%s" % kv for kv in params)


def arcgis_export_url(source: Source, bbox: Sequence[float], sr: int = 3857,
                      width: int = TILE_SIZE, height: int = TILE_SIZE) -> str:
    """ArcGIS MapServer ``/export`` — vyrenderuje libovolný bbox v požadovaném SR."""
    rest = source.extra.get("rest_url") or source.effective_url.split("/WMTS")[0]
    fmt = "jpg" if "jpeg" in source.fmt else "png"
    params = [
        ("bbox", "%.6f,%.6f,%.6f,%.6f" % tuple(bbox)),
        ("bboxSR", str(sr)),
        ("imageSR", str(sr)),
        ("size", "%d,%d" % (width, height)),
        ("format", fmt),
        ("transparent", "false"),
        ("f", "image"),
    ]
    return rest.rstrip("/") + "/export?" + "&".join("%s=%s" % kv for kv in params)


# ===========================================================================
# GDAL helpery (jen pro fallback EPSG:5514 -> 3857)
# ===========================================================================

def have_gdal() -> bool:
    """Je v PATH gdalwarp i gdal_translate?"""
    return bool(shutil.which("gdalwarp") and shutil.which("gdal_translate"))


GDAL_MISSING_MSG = (
    "CHYBA: GDAL není v PATH (chybí gdalwarp / gdal_translate).\n"
    "       Reprojekce z EPSG:5514 do EPSG:3857 se bez něj udělat nedá.\n"
    "       Instalace na macOS:  brew install gdal\n"
    "       Alternativa: použij zdroj/službu, která umí EPSG:3857 nativně "
    "(např. --wms-crs EPSG:3857)."
)


def transform_bbox(bbox: Sequence[float], src_crs: str, dst_crs: str,
                   densify: int = 8) -> Tuple[float, float, float, float]:
    """Přepočte bbox mezi CRS (pyproj, jinak CLI ``gdaltransform``).

    Hrany se zahustí, aby zakřivení Krovaka nezmenšilo výsledný obdélník.
    """
    minx, miny, maxx, maxy = bbox
    points: List[Tuple[float, float]] = []
    for i in range(densify + 1):
        t = i / float(densify)
        points.append((minx + (maxx - minx) * t, miny))
        points.append((minx + (maxx - minx) * t, maxy))
        points.append((minx, miny + (maxy - miny) * t))
        points.append((maxx, miny + (maxy - miny) * t))

    transformed: List[Tuple[float, float]] = []
    try:
        from pyproj import Transformer  # type: ignore

        transformer = Transformer.from_crs(src_crs, dst_crs, always_xy=True)
        for px, py in points:
            tx, ty = transformer.transform(px, py)
            transformed.append((tx, ty))
    except ImportError:
        if not shutil.which("gdaltransform"):
            raise RuntimeError(
                "Pro reprojekci bboxu je potřeba pyproj (pip install pyproj) "
                "nebo GDAL CLI (brew install gdal)."
            )
        stdin = "\n".join("%.9f %.9f" % pt for pt in points) + "\n"
        proc = subprocess.run(
            ["gdaltransform", "-s_srs", src_crs, "-t_srs", dst_crs],
            input=stdin.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=60,
        )
        if proc.returncode != 0:
            raise RuntimeError("gdaltransform selhal: %s" % proc.stderr.decode("utf-8", "replace")[:200])
        for line in proc.stdout.decode("utf-8", "replace").splitlines():
            parts = line.split()
            if len(parts) >= 2:
                transformed.append((float(parts[0]), float(parts[1])))

    if not transformed:
        raise RuntimeError("Transformace bboxu nevrátila žádné body.")
    xs = [p[0] for p in transformed]
    ys = [p[1] for p in transformed]
    return min(xs), min(ys), max(xs), max(ys)


def reproject_image_to_tile(src_bytes: bytes, src_bbox: Sequence[float], src_crs: str,
                            dst_bbox: Sequence[float], dst_path: str,
                            ext: str = "png", resample: str = "lanczos",
                            timeout: float = 120.0) -> None:
    """Zapíše obrázek jako georeferencovaný, warpne ho do 3857 a uloží dlaždici."""
    if not have_gdal():
        raise RuntimeError(GDAL_MISSING_MSG)
    driver = "JPEG" if ext.lower() in ("jpg", "jpeg") else "PNG"
    workdir = tempfile.mkdtemp(prefix="dm_warp_")
    try:
        raw = os.path.join(workdir, "src." + ("jpg" if driver == "JPEG" else "png"))
        with open(raw, "wb") as handle:
            handle.write(src_bytes)
        georef = os.path.join(workdir, "src.tif")
        warped = os.path.join(workdir, "dst.tif")
        minx, miny, maxx, maxy = src_bbox
        subprocess.run(
            ["gdal_translate", "-q", "-of", "GTiff", "-a_srs", src_crs,
             "-a_ullr", "%.6f" % minx, "%.6f" % maxy, "%.6f" % maxx, "%.6f" % miny,
             raw, georef],
            check=True, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        subprocess.run(
            ["gdalwarp", "-q", "-overwrite", "-s_srs", src_crs, "-t_srs", "EPSG:3857",
             "-te", "%.6f" % dst_bbox[0], "%.6f" % dst_bbox[1],
             "%.6f" % dst_bbox[2], "%.6f" % dst_bbox[3],
             "-ts", str(TILE_SIZE), str(TILE_SIZE), "-r", resample,
             georef, warped],
            check=True, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        subprocess.run(
            ["gdal_translate", "-q", "-of", driver, warped, dst_path],
            check=True, timeout=timeout, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# ===========================================================================
# Downloader
# ===========================================================================

class RateLimiter:
    """Jednoduchý globální limiter: nejvýš ``rate`` requestů za sekundu."""

    def __init__(self, rate: float) -> None:
        self._min_interval = 0.0 if rate <= 0 else 1.0 / rate
        self._lock = threading.Lock()
        self._next_at = 0.0

    def acquire(self) -> None:
        if self._min_interval <= 0:
            return
        with self._lock:
            now = time.monotonic()
            wait = self._next_at - now
            if wait < 0:
                wait = 0.0
                self._next_at = now
            self._next_at += self._min_interval
        if wait > 0:
            time.sleep(wait)


class TileFetcher:
    """Stahovač dlaždic — retry, rate limit, resume, odolnost proti chybám."""

    def __init__(self, source: Source, outdir: str, mode: str = "auto",
                 wms_crs: str = "EPSG:3857", wms_version: str = "1.3.0",
                 tile_template: Optional[str] = None, kvp: bool = False,
                 retries: int = 3, timeout: float = 30.0, rate: float = 8.0,
                 workers: int = 4, overwrite: bool = False,
                 resample: str = "lanczos", verbose: bool = False) -> None:
        if requests is None:
            raise RuntimeError(
                "Chybí modul 'requests'. Nainstaluj: python3 -m pip install -r tools/requirements.txt"
            )
        self.source = source
        self.outdir = outdir
        self.mode = mode if mode != "auto" else self._auto_mode(source)
        self.wms_crs = wms_crs
        self.wms_version = wms_version
        self.tile_template = tile_template
        self.kvp = kvp
        self.retries = max(0, retries)
        self.timeout = timeout
        self.limiter = RateLimiter(rate)
        self.workers = max(1, workers)
        self.overwrite = overwrite
        self.resample = resample
        self.verbose = verbose
        self.ext = source.ext
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT, "Accept": "image/*,*/*"})
        self.stats = {"ok": 0, "skipped": 0, "empty": 0, "failed": 0}
        self._stats_lock = threading.Lock()
        self.failures: List[str] = []

    @staticmethod
    def _auto_mode(source: Source) -> str:
        if source.type in ("wmts", "arcgis-wmts"):
            return "wmts"
        if source.type == "wms":
            return "wms"
        if source.type == "arcgis-rest":
            return "arcgis-export"
        raise ValueError(
            "Zdroj typu %r neumí fetch_tiles.py stahovat jako dlaždice." % source.type
        )

    # -- cesty ------------------------------------------------------------
    def tile_path(self, z: int, x: int, y: int) -> str:
        return os.path.join(self.outdir, str(z), str(x), "%d.%s" % (y, self.ext))

    # -- síť --------------------------------------------------------------
    def _get(self, url: str) -> Tuple[Optional[bytes], Optional[str]]:
        """Stáhne URL s retry/backoffem. Vrací (data, chyba)."""
        last_error = "?"
        for attempt in range(self.retries + 1):
            self.limiter.acquire()
            try:
                response = self.session.get(url, timeout=(10.0, self.timeout))
            except Exception as exc:  # noqa: BLE001 - síť smí selhat jakkoli
                last_error = "%s: %s" % (type(exc).__name__, str(exc)[:120])
            else:
                if response.status_code == 200:
                    content = response.content
                    ctype = response.headers.get("Content-Type", "")
                    if content and ("image" in ctype or _looks_like_image(content)):
                        return content, None
                    # Služba vrátila 200 s XML výjimkou / prázdnem
                    snippet = content[:200].decode("utf-8", "replace").replace("\n", " ")
                    last_error = "non-image odpověď (%s): %s" % (ctype, snippet)
                    if "ServiceException" not in snippet and "Exception" not in snippet:
                        return None, last_error  # nemá smysl opakovat
                elif response.status_code in (404, 204):
                    return None, "EMPTY:%d" % response.status_code
                elif response.status_code in (429, 500, 502, 503, 504):
                    last_error = "HTTP %d" % response.status_code
                else:
                    return None, "HTTP %d" % response.status_code
            if attempt < self.retries:
                backoff = (2 ** attempt) * 0.5 + random.uniform(0, 0.3)
                time.sleep(backoff)
        return None, last_error

    # -- jedna dlaždice ---------------------------------------------------
    def fetch_tile(self, z: int, x: int, y: int) -> str:
        """Stáhne jednu dlaždici. Vrací 'ok' | 'skipped' | 'empty' | 'failed'."""
        path = self.tile_path(z, x, y)
        if not self.overwrite and os.path.exists(path) and os.path.getsize(path) > 0:
            self._bump("skipped")
            return "skipped"
        try:
            if self.mode == "wmts":
                if self.kvp or not (self.tile_template or self.source.tile_template):
                    url = wmts_kvp_url(self.source, z, x, y)
                else:
                    url = wmts_rest_url(self.source, z, x, y, self.tile_template)
                data, error = self._get(url)
                if data is None:
                    return self._handle_miss(z, x, y, error)
                self._write(path, data)
            elif self.mode == "wms":
                if self.wms_crs.upper() in ("EPSG:3857", "EPSG:900913"):
                    bbox = tile_bounds_3857(x, y, z)
                    url = wms_getmap_url(self.source, bbox, self.wms_crs,
                                         version=self.wms_version)
                    data, error = self._get(url)
                    if data is None:
                        return self._handle_miss(z, x, y, error)
                    self._write(path, data)
                else:
                    self._fetch_wms_reprojected(z, x, y, path)
            elif self.mode == "arcgis-export":
                bbox = tile_bounds_3857(x, y, z)
                url = arcgis_export_url(self.source, bbox)
                data, error = self._get(url)
                if data is None:
                    return self._handle_miss(z, x, y, error)
                self._write(path, data)
            else:
                raise ValueError("neznámý mód %r" % self.mode)
        except Exception as exc:  # noqa: BLE001 - jedna dlaždice nesmí shodit běh
            return self._handle_miss(z, x, y, "%s: %s" % (type(exc).__name__, str(exc)[:160]))
        self._bump("ok")
        return "ok"

    def _fetch_wms_reprojected(self, z: int, x: int, y: int, path: str) -> None:
        """WMS umí jen EPSG:5514 -> vyžádáme v 5514 a warpneme přes GDAL."""
        if not have_gdal():
            raise RuntimeError(GDAL_MISSING_MSG)
        dst_bbox = tile_bounds_3857(x, y, z)
        src_bbox = transform_bbox(dst_bbox, "EPSG:3857", self.wms_crs)
        # 2x oversampling, aby lanczos měl z čeho brát
        url = wms_getmap_url(self.source, src_bbox, self.wms_crs,
                             width=TILE_SIZE * 2, height=TILE_SIZE * 2,
                             version=self.wms_version)
        data, error = self._get(url)
        if data is None:
            raise RuntimeError(error or "prázdná odpověď")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".part"
        reproject_image_to_tile(data, src_bbox, self.wms_crs, dst_bbox, tmp,
                                ext=self.ext, resample=self.resample)
        os.replace(tmp, path)

    def _write(self, path: str, data: bytes) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".part"
        with open(tmp, "wb") as handle:
            handle.write(data)
        os.replace(tmp, path)

    def _handle_miss(self, z: int, x: int, y: int, error: Optional[str]) -> str:
        if error and error.startswith("EMPTY:"):
            self._bump("empty")
            return "empty"
        message = "%d/%d/%d: %s" % (z, x, y, error)
        with self._stats_lock:
            self.stats["failed"] += 1
            if len(self.failures) < 50:
                self.failures.append(message)
        if self.verbose:
            print("  ! " + message, file=sys.stderr)
        return "failed"

    def _bump(self, key: str) -> None:
        with self._stats_lock:
            self.stats[key] += 1

    # -- dávka ------------------------------------------------------------
    def fetch_many(self, tiles: Sequence[Tuple[int, int, int]],
                   progress: Optional[Callable[[int], None]] = None) -> Dict[str, int]:
        """Stáhne seznam dlaždic přes thread pool; chyby loguje a pokračuje."""
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            futures = [pool.submit(self.fetch_tile, z, x, y) for (z, x, y) in tiles]
            for _ in as_completed(futures):
                if progress:
                    progress(1)
        return dict(self.stats)


def _looks_like_image(data: bytes) -> bool:
    """Rychlá kontrola magic bytes (PNG / JPEG / GIF / WebP)."""
    return (
        data.startswith(b"\x89PNG\r\n\x1a\n")
        or data.startswith(b"\xff\xd8\xff")
        or data.startswith(b"GIF8")
        or (data[:4] == b"RIFF" and data[8:12] == b"WEBP")
    )


def write_meta(outdir: str, source: Source, bbox: Sequence[float], zooms: Sequence[int],
               mode: str) -> str:
    """Uloží ``_meta.json`` — build_pmtiles.py z něj bere atribuci a rozsahy."""
    meta = {
        "source_id": source.id,
        "title": source.title,
        "attribution": source.attribution,
        "format": source.ext,
        "mime": source.fmt,
        "bbox_wgs84": list(bbox),
        "minzoom": min(zooms),
        "maxzoom": max(zooms),
        "mode": mode,
        "generated_by": "tools/fetch_tiles.py",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "licence": LICENCE_WARNING,
    }
    path = os.path.join(outdir, "_meta.json")
    os.makedirs(outdir, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(meta, handle, indent=2, ensure_ascii=False)
    return path


def human_size(num_bytes: float) -> str:
    """Bajty -> čitelný řetězec."""
    for unit in ("B", "kB", "MB", "GB", "TB"):
        if abs(num_bytes) < 1024.0:
            return "%.1f %s" % (num_bytes, unit)
        num_bytes /= 1024.0
    return "%.1f PB" % num_bytes


def main(argv: Optional[List[str]] = None) -> int:
    """Stáhne dlaždice pro zadaný bbox a zoomy do adresáře {z}/{x}/{y}."""
    parser = argparse.ArgumentParser(
        description="Stažení WMTS/WMS dlaždic pro bbox a rozsah zoomů (F1-4).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
        epilog="bbox se zadává ve WGS84 jako: --bbox ZÁPAD JIH VÝCHOD SEVER",
    )
    parser.add_argument("--source", required=True, help="id zdroje z tools/sources.py")
    parser.add_argument("--bbox", nargs=4, type=float, required=True,
                        metavar=("W", "S", "E", "N"), help="bbox ve WGS84 (lon/lat)")
    parser.add_argument("--zoom", required=True, help="rozsah zoomů, např. 12-16 nebo 10,12,14")
    parser.add_argument("--out", required=True, help="výstupní adresář pro {z}/{x}/{y}")
    parser.add_argument("--mode", choices=("auto", "wmts", "wms", "arcgis-export"),
                        default="auto", help="jak dlaždice získat")
    parser.add_argument("--tile-template", help="vlastní RESTful/XYZ šablona s {z}/{x}/{y}")
    parser.add_argument("--kvp", action="store_true", help="WMTS přes KVP GetTile místo REST šablony")
    parser.add_argument("--wms-crs", default="EPSG:3857",
                        help="CRS pro WMS GetMap; při EPSG:5514 se reprojektuje přes GDAL")
    parser.add_argument("--wms-version", default="1.3.0", choices=("1.1.1", "1.3.0"))
    parser.add_argument("--workers", type=int, default=4, help="počet vláken")
    parser.add_argument("--rate", type=float, default=8.0, help="max. requestů za sekundu (0 = bez limitu)")
    parser.add_argument("--retries", type=int, default=3, help="počet opakování při chybě")
    parser.add_argument("--timeout", type=float, default=30.0, help="timeout requestu v sekundách")
    parser.add_argument("--resample", default="lanczos", help="metoda převzorkování pro gdalwarp")
    parser.add_argument("--overwrite", action="store_true", help="přepsat i existující dlaždice")
    parser.add_argument("--dry-run", action="store_true",
                        help="jen spočítat dlaždice a odhadnout velikost, nic nestahovat")
    parser.add_argument("--avg-tile-kb", type=float, default=0.0,
                        help="odhad velikosti dlaždice v kB pro --dry-run (0 = podle formátu)")
    parser.add_argument("--limit", type=int, default=0, help="stáhnout nejvýš N dlaždic (test)")
    parser.add_argument("--verbose", action="store_true", help="vypisovat každou chybu")
    args = parser.parse_args(argv)

    try:
        source = get_source(args.source)
    except KeyError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    try:
        zooms = parse_zoom_range(args.zoom)
    except ValueError as exc:
        print("CHYBA: %s" % exc, file=sys.stderr)
        return 2

    west, south, east, north = args.bbox
    if west >= east or south >= north:
        print("CHYBA: neplatný bbox, čekám --bbox ZÁPAD JIH VÝCHOD SEVER", file=sys.stderr)
        return 2
    bbox = (west, south, east, north)

    outside = [z for z in zooms if z < source.min_zoom or z > source.max_zoom]
    if outside:
        print("POZOR: zoomy %s jsou mimo deklarovaný rozsah zdroje (%d-%d)."
              % (outside, source.min_zoom, source.max_zoom), file=sys.stderr)

    tiles: List[Tuple[int, int, int]] = []
    for zoom in zooms:
        tiles.extend(tiles_in_bbox(bbox, zoom))
    if args.limit:
        tiles = tiles[: args.limit]

    avg_kb = args.avg_tile_kb or (45.0 if source.ext in ("jpg", "jpeg") else 25.0)
    print("Zdroj:   %s (%s, %s)" % (source.id, source.type, source.crs))
    print("bbox:    %.5f %.5f %.5f %.5f" % bbox)
    print("Zoomy:   %s" % ", ".join(str(z) for z in zooms))
    for zoom in zooms:
        xmin, ymin, xmax, ymax = tile_range(bbox, zoom)
        print("  z=%-2d  x %d..%d  y %d..%d  = %d dlaždic"
              % (zoom, xmin, xmax, ymin, ymax, (xmax - xmin + 1) * (ymax - ymin + 1)))
    print("Celkem:  %d dlaždic, odhad ~%s (%.0f kB/dlaždice)"
          % (len(tiles), human_size(len(tiles) * avg_kb * 1024), avg_kb))

    if args.dry_run:
        print("\n--dry-run: nic se nestahovalo.")
        print(LICENCE_WARNING)
        return 0

    if requests is None:
        print("CHYBA: chybí modul 'requests' (pip install -r tools/requirements.txt)", file=sys.stderr)
        return 2

    try:
        fetcher = TileFetcher(
            source, args.out, mode=args.mode, wms_crs=args.wms_crs,
            wms_version=args.wms_version, tile_template=args.tile_template,
            kvp=args.kvp, retries=args.retries, timeout=args.timeout,
            rate=args.rate, workers=args.workers, overwrite=args.overwrite,
            resample=args.resample, verbose=args.verbose,
        )
    except (ValueError, RuntimeError) as exc:
        print("CHYBA: %s" % exc, file=sys.stderr)
        return 2

    if fetcher.mode == "wms" and args.wms_crs.upper() != "EPSG:3857" and not have_gdal():
        print(GDAL_MISSING_MSG, file=sys.stderr)
        return 2

    os.makedirs(args.out, exist_ok=True)
    print("\nStahuji do %s (mód %s, %d vláken)…" % (args.out, fetcher.mode, fetcher.workers))
    started = time.time()
    if tqdm is not None:
        bar = tqdm(total=len(tiles), unit="tile")
        stats = fetcher.fetch_many(tiles, progress=lambda n: bar.update(n))
        bar.close()
    else:
        counter = {"n": 0}

        def _progress(step: int) -> None:
            counter["n"] += step
            if counter["n"] % 50 == 0 or counter["n"] == len(tiles):
                sys.stderr.write("\r  %d/%d" % (counter["n"], len(tiles)))
                sys.stderr.flush()

        stats = fetcher.fetch_many(tiles, progress=_progress)
        sys.stderr.write("\n")

    elapsed = time.time() - started
    meta_path = write_meta(args.out, source, bbox, zooms, fetcher.mode)
    print("Hotovo za %.1f s: %d staženo, %d přeskočeno, %d prázdných, %d selhalo."
          % (elapsed, stats["ok"], stats["skipped"], stats["empty"], stats["failed"]))
    print("Metadata: %s" % meta_path)
    if fetcher.failures:
        print("\nPrvních %d chyb:" % len(fetcher.failures))
        for line in fetcher.failures:
            print("  " + line)
    print("\nDalší krok: python3 tools/build_pmtiles.py --tiles %s --out <vrstva>.pmtiles"
          % args.out)
    print(LICENCE_WARNING)
    return 0 if stats["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
