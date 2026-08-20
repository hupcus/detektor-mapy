#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Převod adresáře ``{z}/{x}/{y}.{ext}`` na archiv **PMTiles v3** (F1-4).

Writer i reader jsou implementované přímo tady v čistém Pythonu podle
specifikace PMTiles v3 (127bajtová hlavička, kořenový adresář, listové
adresáře, JSON metadata, sekce dat, varint-kódované položky s run-length
kompresí, řazení dlaždic po Hilbertově křivce). Žádná externí knihovna není
potřeba. Pokud je ale balíček ``pmtiles`` naimportovatelný, skript ho použije
(a napíše to do výstupu) — je to referenční implementace.

Dlaždice se **deduplikují podle SHA-256 obsahu**; u historických map to ušetří
hodně místa (prázdné/bílé dlaždice na okrajích).

Příklad::

    python3 tools/build_pmtiles.py --tiles data/tiles/ii_vm_tabor \\
        --out data/pmtiles/ii_vm_tabor.pmtiles --name "II. VM — Tábor"
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import sys
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Tuple

try:
    from tqdm import tqdm
except ImportError:  # pragma: no cover
    tqdm = None  # type: ignore[assignment]

try:
    from sources import LICENCE_WARNING
except ImportError:  # spuštěno mimo adresář tools/
    try:
        from tools.sources import LICENCE_WARNING  # type: ignore
    except ImportError:  # pragma: no cover
        LICENCE_WARNING = ""

# ---------------------------------------------------------------------------
# Konstanty specifikace PMTiles v3
# ---------------------------------------------------------------------------
PMTILES_MAGIC = b"PMTiles"
PMTILES_VERSION = 3
HEADER_SIZE = 127
MAX_ROOT_DIR_BYTES = 16384 - HEADER_SIZE  # root musí padnout do prvních 16 kB

# internal/tile compression enum
COMPRESSION_UNKNOWN = 0
COMPRESSION_NONE = 1
COMPRESSION_GZIP = 2
COMPRESSION_BROTLI = 3
COMPRESSION_ZSTD = 4

# tile type enum
TILETYPE_UNKNOWN = 0
TILETYPE_MVT = 1
TILETYPE_PNG = 2
TILETYPE_JPEG = 3
TILETYPE_WEBP = 4
TILETYPE_AVIF = 5

EXT_TO_TILETYPE = {
    "pbf": TILETYPE_MVT, "mvt": TILETYPE_MVT,
    "png": TILETYPE_PNG,
    "jpg": TILETYPE_JPEG, "jpeg": TILETYPE_JPEG,
    "webp": TILETYPE_WEBP,
    "avif": TILETYPE_AVIF,
}
TILETYPE_TO_MIME = {
    TILETYPE_MVT: "application/vnd.mapbox-vector-tile",
    TILETYPE_PNG: "image/png",
    TILETYPE_JPEG: "image/jpeg",
    TILETYPE_WEBP: "image/webp",
    TILETYPE_AVIF: "image/avif",
}


# ===========================================================================
# Varint (protobuf-style, unsigned LEB128)
# ===========================================================================

def write_varint(value: int) -> bytes:
    """Zakóduje nezáporné celé číslo jako unsigned varint (LEB128)."""
    if value < 0:
        raise ValueError("varint neumí záporná čísla: %d" % value)
    out = bytearray()
    while True:
        chunk = value & 0x7F
        value >>= 7
        if value:
            out.append(chunk | 0x80)
        else:
            out.append(chunk)
            return bytes(out)


def read_varint(buf: bytes, pos: int = 0) -> Tuple[int, int]:
    """Dekóduje varint z ``buf`` od pozice ``pos``. Vrací (hodnota, nová pozice)."""
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("varint přesahuje konec bufferu")
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7
        if shift > 63:
            raise ValueError("varint je příliš dlouhý")


# ===========================================================================
# Hilbertovo pořadí dlaždic (tile id)
# ===========================================================================

def zxy_to_tileid(z: int, x: int, y: int) -> int:
    """(z, x, y) -> tile id podle Hilbertovy křivky (PMTiles v3)."""
    if z < 0 or z > 31:
        raise ValueError("zoom mimo rozsah 0..31: %d" % z)
    n = 1 << z
    if not (0 <= x < n and 0 <= y < n):
        raise ValueError("dlaždice %d/%d/%d je mimo rozsah zoomu" % (z, x, y))
    # Počet dlaždic na všech nižších zoomech: (4^z - 1) / 3
    acc = ((1 << (z * 2)) - 1) // 3
    tx, ty, d = x, y, 0
    s = n >> 1
    while s > 0:
        rx = 1 if (tx & s) > 0 else 0
        ry = 1 if (ty & s) > 0 else 0
        d += s * s * ((3 * rx) ^ ry)
        # rotace kvadrantu
        if ry == 0:
            if rx == 1:
                tx = s - 1 - tx
                ty = s - 1 - ty
            tx, ty = ty, tx
        s >>= 1
    return acc + d


def tileid_to_zxy(tile_id: int) -> Tuple[int, int, int]:
    """tile id -> (z, x, y). Inverze k :func:`zxy_to_tileid`."""
    if tile_id < 0:
        raise ValueError("tile id nesmí být záporné")
    acc = 0
    z = 0
    while True:
        num_tiles = 1 << (z * 2)
        if tile_id < acc + num_tiles:
            break
        acc += num_tiles
        z += 1
        if z > 31:
            raise ValueError("tile id je mimo podporovaný rozsah")
    d = tile_id - acc
    n = 1 << z
    tx = ty = 0
    s = 1
    while s < n:
        rx = 1 & (d >> 1)
        ry = 1 & (d ^ rx)
        if ry == 0:
            if rx == 1:
                tx = s - 1 - tx
                ty = s - 1 - ty
            tx, ty = ty, tx
        tx += s * rx
        ty += s * ry
        d >>= 2
        s <<= 1
    return z, tx, ty


# ===========================================================================
# Adresáře
# ===========================================================================

class Entry(object):
    """Jedna položka adresáře PMTiles."""

    __slots__ = ("tile_id", "offset", "length", "run_length")

    def __init__(self, tile_id: int, offset: int, length: int, run_length: int) -> None:
        self.tile_id = tile_id
        self.offset = offset
        self.length = length
        self.run_length = run_length

    def __repr__(self) -> str:  # pragma: no cover - jen pro ladění
        return "Entry(id=%d, off=%d, len=%d, run=%d)" % (
            self.tile_id, self.offset, self.length, self.run_length)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Entry):
            return NotImplemented
        return (self.tile_id, self.offset, self.length, self.run_length) == (
            other.tile_id, other.offset, other.length, other.run_length)


def serialize_directory(entries: Sequence[Entry]) -> bytes:
    """Serializuje adresář do nekomprimované binární podoby dle spec v3.

    Layout: počet položek, delty tile_id, run_length, length, offset
    (offset 0 = navazuje přímo na předchozí položku).
    """
    out = bytearray()
    out += write_varint(len(entries))

    last_id = 0
    for entry in entries:
        out += write_varint(entry.tile_id - last_id)
        last_id = entry.tile_id
    for entry in entries:
        out += write_varint(entry.run_length)
    for entry in entries:
        out += write_varint(entry.length)
    for index, entry in enumerate(entries):
        if index > 0 and entry.offset == entries[index - 1].offset + entries[index - 1].length:
            out += write_varint(0)
        else:
            out += write_varint(entry.offset + 1)
    return bytes(out)


def deserialize_directory(data: bytes) -> List[Entry]:
    """Opak :func:`serialize_directory`."""
    pos = 0
    count, pos = read_varint(data, pos)
    entries = [Entry(0, 0, 0, 0) for _ in range(count)]

    last_id = 0
    for i in range(count):
        delta, pos = read_varint(data, pos)
        last_id += delta
        entries[i].tile_id = last_id
    for i in range(count):
        entries[i].run_length, pos = read_varint(data, pos)
    for i in range(count):
        entries[i].length, pos = read_varint(data, pos)
    for i in range(count):
        raw, pos = read_varint(data, pos)
        if raw == 0 and i > 0:
            entries[i].offset = entries[i - 1].offset + entries[i - 1].length
        else:
            entries[i].offset = raw - 1
    return entries


def _gzip_bytes(data: bytes) -> bytes:
    """Deterministický gzip (mtime=0), aby byl build reprodukovatelný."""
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", compresslevel=9, mtime=0) as handle:
        handle.write(data)
    return buffer.getvalue()


def _compress(data: bytes, compression: int) -> bytes:
    if compression == COMPRESSION_GZIP:
        return _gzip_bytes(data)
    if compression in (COMPRESSION_NONE, COMPRESSION_UNKNOWN):
        return data
    raise ValueError("nepodporovaná komprese: %d" % compression)


def _decompress(data: bytes, compression: int) -> bytes:
    if compression == COMPRESSION_GZIP:
        return gzip.decompress(data)
    if compression in (COMPRESSION_NONE, COMPRESSION_UNKNOWN):
        return data
    raise ValueError("nepodporovaná komprese: %d" % compression)


def optimize_directories(entries: Sequence[Entry], compression: int,
                         target_root_bytes: int = MAX_ROOT_DIR_BYTES
                         ) -> Tuple[bytes, bytes, int]:
    """Rozdělí položky na kořenový adresář + listové adresáře.

    Vrací (komprimovaný root, komprimované leaves, počet listových adresářů).
    Nejdřív zkusí vše do rootu; když se nevejde do 16 kB, dělí na listy a
    zdvojnásobuje jejich velikost, dokud se root nevejde.
    """
    if not entries:
        return _compress(serialize_directory([]), compression), b"", 0

    root_only = _compress(serialize_directory(entries), compression)
    if len(root_only) <= target_root_bytes:
        return root_only, b"", 0

    leaf_size = 4096
    while True:
        root_entries: List[Entry] = []
        leaves = bytearray()
        for start in range(0, len(entries), leaf_size):
            chunk = entries[start:start + leaf_size]
            serialized = _compress(serialize_directory(chunk), compression)
            root_entries.append(Entry(chunk[0].tile_id, len(leaves), len(serialized), 0))
            leaves += serialized
        root = _compress(serialize_directory(root_entries), compression)
        if len(root) <= target_root_bytes or leaf_size > (1 << 24):
            return root, bytes(leaves), len(root_entries)
        leaf_size *= 2


# ===========================================================================
# Hlavička
# ===========================================================================

def _u64(value: int) -> bytes:
    return int(value).to_bytes(8, "little", signed=False)


def _i32(value: int) -> bytes:
    return int(value).to_bytes(4, "little", signed=True)


class Header(object):
    """127bajtová hlavička PMTiles v3."""

    def __init__(self) -> None:
        self.root_offset = HEADER_SIZE
        self.root_length = 0
        self.metadata_offset = 0
        self.metadata_length = 0
        self.leaf_offset = 0
        self.leaf_length = 0
        self.data_offset = 0
        self.data_length = 0
        self.addressed_tiles = 0
        self.tile_entries = 0
        self.tile_contents = 0
        self.clustered = True
        self.internal_compression = COMPRESSION_GZIP
        self.tile_compression = COMPRESSION_NONE
        self.tile_type = TILETYPE_PNG
        self.min_zoom = 0
        self.max_zoom = 0
        self.min_lon_e7 = 0
        self.min_lat_e7 = 0
        self.max_lon_e7 = 0
        self.max_lat_e7 = 0
        self.center_zoom = 0
        self.center_lon_e7 = 0
        self.center_lat_e7 = 0

    def serialize(self) -> bytes:
        out = bytearray()
        out += PMTILES_MAGIC                    # 0..6
        out += bytes([PMTILES_VERSION])         # 7
        out += _u64(self.root_offset)           # 8
        out += _u64(self.root_length)           # 16
        out += _u64(self.metadata_offset)       # 24
        out += _u64(self.metadata_length)       # 32
        out += _u64(self.leaf_offset)           # 40
        out += _u64(self.leaf_length)           # 48
        out += _u64(self.data_offset)           # 56
        out += _u64(self.data_length)           # 64
        out += _u64(self.addressed_tiles)       # 72
        out += _u64(self.tile_entries)          # 80
        out += _u64(self.tile_contents)         # 88
        out += bytes([1 if self.clustered else 0])   # 96
        out += bytes([self.internal_compression])    # 97
        out += bytes([self.tile_compression])        # 98
        out += bytes([self.tile_type])               # 99
        out += bytes([self.min_zoom])                # 100
        out += bytes([self.max_zoom])                # 101
        out += _i32(self.min_lon_e7)                 # 102
        out += _i32(self.min_lat_e7)                 # 106
        out += _i32(self.max_lon_e7)                 # 110
        out += _i32(self.max_lat_e7)                 # 114
        out += bytes([self.center_zoom])             # 118
        out += _i32(self.center_lon_e7)              # 119
        out += _i32(self.center_lat_e7)              # 123
        if len(out) != HEADER_SIZE:  # pragma: no cover - pojistka proti překlepu
            raise AssertionError("hlavička má %d B místo %d" % (len(out), HEADER_SIZE))
        return bytes(out)

    @classmethod
    def deserialize(cls, data: bytes) -> "Header":
        if len(data) < HEADER_SIZE:
            raise ValueError("hlavička je kratší než %d bajtů" % HEADER_SIZE)
        if data[0:7] != PMTILES_MAGIC:
            raise ValueError("chybí magic 'PMTiles' — tohle není PMTiles archiv")
        if data[7] != PMTILES_VERSION:
            raise ValueError("nepodporovaná verze PMTiles: %d" % data[7])

        def u64(offset: int) -> int:
            return int.from_bytes(data[offset:offset + 8], "little", signed=False)

        def i32(offset: int) -> int:
            return int.from_bytes(data[offset:offset + 4], "little", signed=True)

        header = cls()
        header.root_offset = u64(8)
        header.root_length = u64(16)
        header.metadata_offset = u64(24)
        header.metadata_length = u64(32)
        header.leaf_offset = u64(40)
        header.leaf_length = u64(48)
        header.data_offset = u64(56)
        header.data_length = u64(64)
        header.addressed_tiles = u64(72)
        header.tile_entries = u64(80)
        header.tile_contents = u64(88)
        header.clustered = bool(data[96])
        header.internal_compression = data[97]
        header.tile_compression = data[98]
        header.tile_type = data[99]
        header.min_zoom = data[100]
        header.max_zoom = data[101]
        header.min_lon_e7 = i32(102)
        header.min_lat_e7 = i32(106)
        header.max_lon_e7 = i32(110)
        header.max_lat_e7 = i32(114)
        header.center_zoom = data[118]
        header.center_lon_e7 = i32(119)
        header.center_lat_e7 = i32(123)
        return header


# ===========================================================================
# Writer
# ===========================================================================

class PMTilesWriter(object):
    """Zapisovač PMTiles v3 s deduplikací dlaždic podle SHA-256.

    Dlaždice se musí přidávat **vzestupně podle tile id** (viz
    :func:`zxy_to_tileid`) — archiv je pak "clustered" a čte se sekvenčně.
    """

    def __init__(self, tile_type: int = TILETYPE_PNG,
                 tile_compression: int = COMPRESSION_NONE,
                 internal_compression: int = COMPRESSION_GZIP,
                 dedup: bool = True,
                 max_root_bytes: int = MAX_ROOT_DIR_BYTES) -> None:
        self.tile_type = tile_type
        self.tile_compression = tile_compression
        self.internal_compression = internal_compression
        self.dedup = dedup
        # Rozpočet na kořenový adresář; menší hodnota vynutí listové adresáře
        # (spec doporučuje, aby se root vešel do prvních 16 kB souboru).
        self.max_root_bytes = max_root_bytes
        self.entries: List[Entry] = []
        self.data = bytearray()
        self._hash_to_offset: Dict[bytes, Tuple[int, int]] = {}
        self._last_hash: Optional[bytes] = None
        self.addressed_tiles = 0
        self.unique_contents = 0
        self._last_tile_id = -1

    def add_tile(self, tile_id: int, data: bytes) -> None:
        """Přidá dlaždici; run-length kompresí spojí sousední shodné dlaždice."""
        if tile_id <= self._last_tile_id:
            raise ValueError(
                "dlaždice musí jít vzestupně podle tile id (%d po %d)"
                % (tile_id, self._last_tile_id)
            )
        self.addressed_tiles += 1
        digest = hashlib.sha256(data).digest() if self.dedup else os.urandom(32)

        # 1) navazuje přímo na předchozí položku se stejným obsahem -> run-length
        if (
            self.dedup
            and self.entries
            and digest == self._last_hash
            and self.entries[-1].tile_id + self.entries[-1].run_length == tile_id
        ):
            self.entries[-1].run_length += 1
            self._last_tile_id = tile_id
            return

        # 2) stejný obsah už někde je -> nová položka, stejný offset
        if self.dedup and digest in self._hash_to_offset:
            offset, length = self._hash_to_offset[digest]
            self.entries.append(Entry(tile_id, offset, length, 1))
        else:
            offset = len(self.data)
            self.data += data
            if self.dedup:
                self._hash_to_offset[digest] = (offset, len(data))
            self.unique_contents += 1
            self.entries.append(Entry(tile_id, offset, len(data), 1))

        self._last_hash = digest
        self._last_tile_id = tile_id

    def add_zxy(self, z: int, x: int, y: int, data: bytes) -> None:
        """Pohodlnější varianta :meth:`add_tile` přes souřadnice dlaždice."""
        self.add_tile(zxy_to_tileid(z, x, y), data)

    def finalize(self, metadata: Dict[str, object], min_zoom: int, max_zoom: int,
                 bounds: Sequence[float], center: Optional[Sequence[float]] = None,
                 center_zoom: Optional[int] = None) -> bytes:
        """Sestaví celý archiv a vrátí ho jako bajty."""
        root, leaves, _ = optimize_directories(
            self.entries, self.internal_compression, self.max_root_bytes)
        metadata_bytes = _compress(
            json.dumps(metadata, ensure_ascii=False, sort_keys=True).encode("utf-8"),
            self.internal_compression,
        )

        header = Header()
        header.internal_compression = self.internal_compression
        header.tile_compression = self.tile_compression
        header.tile_type = self.tile_type
        header.clustered = True
        header.root_offset = HEADER_SIZE
        header.root_length = len(root)
        header.metadata_offset = header.root_offset + header.root_length
        header.metadata_length = len(metadata_bytes)
        header.leaf_offset = header.metadata_offset + header.metadata_length
        header.leaf_length = len(leaves)
        header.data_offset = header.leaf_offset + header.leaf_length
        header.data_length = len(self.data)
        header.addressed_tiles = self.addressed_tiles
        header.tile_entries = len(self.entries)
        header.tile_contents = self.unique_contents
        header.min_zoom = min_zoom
        header.max_zoom = max_zoom
        west, south, east, north = bounds
        header.min_lon_e7 = int(round(west * 1e7))
        header.min_lat_e7 = int(round(south * 1e7))
        header.max_lon_e7 = int(round(east * 1e7))
        header.max_lat_e7 = int(round(north * 1e7))
        if center is None:
            center = ((west + east) / 2.0, (south + north) / 2.0)
        header.center_lon_e7 = int(round(center[0] * 1e7))
        header.center_lat_e7 = int(round(center[1] * 1e7))
        header.center_zoom = center_zoom if center_zoom is not None else min_zoom

        return b"".join([header.serialize(), root, metadata_bytes, leaves, bytes(self.data)])

    def write(self, path: str, metadata: Dict[str, object], min_zoom: int, max_zoom: int,
              bounds: Sequence[float], center: Optional[Sequence[float]] = None,
              center_zoom: Optional[int] = None) -> int:
        """Zapíše archiv na disk (atomicky přes ``.part``). Vrací velikost v bajtech."""
        blob = self.finalize(metadata, min_zoom, max_zoom, bounds, center, center_zoom)
        directory = os.path.dirname(os.path.abspath(path))
        if directory:
            os.makedirs(directory, exist_ok=True)
        tmp = path + ".part"
        with open(tmp, "wb") as handle:
            handle.write(blob)
        os.replace(tmp, path)
        return len(blob)


# ===========================================================================
# Reader (pro testy a ověření výstupu)
# ===========================================================================

class PMTilesReader(object):
    """Minimální čtečka PMTiles v3 — hlavička, metadata, ``get_tile``."""

    def __init__(self, data: bytes) -> None:
        self.raw = data
        self.header = Header.deserialize(data[:HEADER_SIZE])

    @classmethod
    def from_file(cls, path: str) -> "PMTilesReader":
        with open(path, "rb") as handle:
            return cls(handle.read())

    def _slice(self, offset: int, length: int) -> bytes:
        return self.raw[offset:offset + length]

    def metadata(self) -> Dict[str, object]:
        """Vrátí JSON metadata archivu."""
        raw = self._slice(self.header.metadata_offset, self.header.metadata_length)
        if not raw:
            return {}
        text = _decompress(raw, self.header.internal_compression).decode("utf-8")
        return json.loads(text)

    def root_entries(self) -> List[Entry]:
        """Položky kořenového adresáře."""
        raw = self._slice(self.header.root_offset, self.header.root_length)
        return deserialize_directory(_decompress(raw, self.header.internal_compression))

    @staticmethod
    def find_entry(entries: Sequence[Entry], tile_id: int) -> Optional[Entry]:
        """Binární vyhledání položky pokrývající ``tile_id``."""
        lo, hi = 0, len(entries) - 1
        found = -1
        while lo <= hi:
            mid = (lo + hi) // 2
            if entries[mid].tile_id > tile_id:
                hi = mid - 1
            else:
                found = mid
                lo = mid + 1
        if found < 0:
            return None
        entry = entries[found]
        if entry.run_length == 0:
            return entry  # odkaz na listový adresář
        if tile_id < entry.tile_id + entry.run_length:
            return entry
        return None

    def get_tile(self, z: int, x: int, y: int) -> Optional[bytes]:
        """Vrátí data dlaždice, nebo ``None`` když v archivu není."""
        tile_id = zxy_to_tileid(z, x, y)
        entries = self.root_entries()
        for _ in range(4):  # spec dovoluje nejvýš 3 úrovně; 4 je bezpečná pojistka
            entry = self.find_entry(entries, tile_id)
            if entry is None:
                return None
            if entry.run_length == 0:
                raw = self._slice(self.header.leaf_offset + entry.offset, entry.length)
                entries = deserialize_directory(
                    _decompress(raw, self.header.internal_compression))
                continue
            return self._slice(self.header.data_offset + entry.offset, entry.length)
        return None

    def all_tile_ids(self) -> Iterator[int]:
        """Projde všechna adresovaná tile id (rozbalí run-length)."""
        stack: List[List[Entry]] = [self.root_entries()]
        while stack:
            entries = stack.pop(0)
            for entry in entries:
                if entry.run_length == 0:
                    raw = self._slice(self.header.leaf_offset + entry.offset, entry.length)
                    stack.append(deserialize_directory(
                        _decompress(raw, self.header.internal_compression)))
                else:
                    for i in range(entry.run_length):
                        yield entry.tile_id + i


# ===========================================================================
# Skenování adresáře s dlaždicemi
# ===========================================================================

def scan_tile_dir(tiles_dir: str, ext: Optional[str] = None
                  ) -> Tuple[List[Tuple[int, int, int, str]], str]:
    """Projde ``{z}/{x}/{y}.{ext}`` a vrátí (seznam dlaždic, zjištěná přípona).

    Seznam je seřazený podle Hilbertova tile id, což writer vyžaduje.
    """
    found: List[Tuple[int, int, int, str]] = []
    detected_ext = ext
    if not os.path.isdir(tiles_dir):
        raise ValueError("adresář s dlaždicemi neexistuje: %s" % tiles_dir)

    for z_name in sorted(os.listdir(tiles_dir)):
        z_path = os.path.join(tiles_dir, z_name)
        if not (os.path.isdir(z_path) and z_name.isdigit()):
            continue
        z = int(z_name)
        for x_name in sorted(os.listdir(z_path)):
            x_path = os.path.join(z_path, x_name)
            if not (os.path.isdir(x_path) and x_name.isdigit()):
                continue
            x = int(x_name)
            for y_name in sorted(os.listdir(x_path)):
                if y_name.startswith(".") or y_name.endswith(".part"):
                    continue
                stem, _, file_ext = y_name.rpartition(".")
                if not stem.isdigit():
                    continue
                if ext and file_ext.lower() != ext.lower():
                    continue
                if detected_ext is None:
                    detected_ext = file_ext.lower()
                path = os.path.join(x_path, y_name)
                if os.path.getsize(path) == 0:
                    continue
                found.append((z, x, int(stem), path))

    found.sort(key=lambda t: zxy_to_tileid(t[0], t[1], t[2]))
    return found, (detected_ext or "png")


def bounds_from_tiles(tiles: Iterable[Tuple[int, int, int, str]]
                      ) -> Tuple[float, float, float, float]:
    """Spočítá WGS84 bbox pokrytý dlaždicemi (podle nejnižšího zoomu)."""
    try:  # lokální import kvůli volným závislostem mezi skripty
        from fetch_tiles import tile_bounds_wgs84
    except ImportError:  # spuštěno jako balíček tools.*
        from tools.fetch_tiles import tile_bounds_wgs84  # type: ignore

    by_zoom: Dict[int, List[Tuple[int, int]]] = {}
    for z, x, y, _path in tiles:
        by_zoom.setdefault(z, []).append((x, y))
    if not by_zoom:
        return (-180.0, -85.0, 180.0, 85.0)
    zoom = min(by_zoom)
    xs = [xy[0] for xy in by_zoom[zoom]]
    ys = [xy[1] for xy in by_zoom[zoom]]
    west, _s, _e, north = tile_bounds_wgs84(min(xs), min(ys), zoom)
    _w, south, east, _n = tile_bounds_wgs84(max(xs), max(ys), zoom)
    return west, south, east, north


def build_from_directory(tiles_dir: str, out_path: str, name: str = "",
                         attribution: str = "", ext: Optional[str] = None,
                         dedup: bool = True, extra_metadata: Optional[Dict[str, object]] = None,
                         quiet: bool = False) -> Dict[str, object]:
    """Postaví PMTiles z adresáře dlaždic. Vrací souhrn pro layers.json."""
    tiles, detected_ext = scan_tile_dir(tiles_dir, ext)
    if not tiles:
        raise ValueError("v %s nejsou žádné dlaždice ve tvaru {z}/{x}/{y}.{ext}" % tiles_dir)

    meta_sidecar: Dict[str, object] = {}
    sidecar_path = os.path.join(tiles_dir, "_meta.json")
    if os.path.exists(sidecar_path):
        try:
            with open(sidecar_path, "r", encoding="utf-8") as handle:
                meta_sidecar = json.load(handle)
        except (ValueError, OSError):
            meta_sidecar = {}

    tile_type = EXT_TO_TILETYPE.get(detected_ext, TILETYPE_UNKNOWN)
    tile_compression = COMPRESSION_GZIP if tile_type == TILETYPE_MVT else COMPRESSION_NONE
    zooms = sorted({t[0] for t in tiles})
    bounds = meta_sidecar.get("bbox_wgs84") or list(bounds_from_tiles(tiles))
    title = name or str(meta_sidecar.get("title") or os.path.basename(tiles_dir.rstrip("/")))
    attr = attribution or str(meta_sidecar.get("attribution") or "")

    writer = PMTilesWriter(tile_type=tile_type, tile_compression=tile_compression, dedup=dedup)
    iterator: Iterable[Tuple[int, int, int, str]] = tiles
    if tqdm is not None and not quiet:
        iterator = tqdm(tiles, unit="tile")
    skipped = 0
    for z, x, y, path in iterator:
        try:
            with open(path, "rb") as handle:
                blob = handle.read()
        except OSError as exc:
            skipped += 1
            if not quiet:
                print("  ! nelze číst %s: %s" % (path, exc), file=sys.stderr)
            continue
        if not blob:
            skipped += 1
            continue
        try:
            writer.add_zxy(z, x, y, blob)
        except ValueError as exc:
            skipped += 1
            if not quiet:
                print("  ! dlaždice %d/%d/%d přeskočena: %s" % (z, x, y, exc), file=sys.stderr)

    metadata: Dict[str, object] = {
        "name": title,
        "type": "overlay",
        "format": detected_ext,
        "attribution": attr,
        "description": "DetektorMapy — %s" % title,
        "minzoom": str(min(zooms)),
        "maxzoom": str(max(zooms)),
        "bounds": ",".join("%.6f" % v for v in bounds),
        "generated_by": "tools/build_pmtiles.py",
    }
    if meta_sidecar.get("source_id"):
        metadata["source_id"] = meta_sidecar["source_id"]
    if extra_metadata:
        metadata.update(extra_metadata)

    size = writer.write(out_path, metadata, min(zooms), max(zooms), bounds)

    return {
        "path": out_path,
        "bytes": size,
        "tiles": len(tiles),
        "skipped": skipped,
        "entries": len(writer.entries),
        "unique": writer.unique_contents,
        "minzoom": min(zooms),
        "maxzoom": max(zooms),
        "bounds": list(bounds),
        "title": title,
        "attribution": attr,
        "format": detected_ext,
        "mime": TILETYPE_TO_MIME.get(tile_type, "application/octet-stream"),
    }


def build_with_pmtiles_package(tiles_dir: str, out_path: str, summary_hint: Dict[str, object]
                               ) -> Optional[Dict[str, object]]:
    """Pokud je nainstalovaný balíček ``pmtiles``, použije jeho writer.

    Vrací ``None``, když balíček není k dispozici nebo jeho API nesedí.
    """
    try:
        from pmtiles.writer import Writer  # type: ignore
        from pmtiles.tile import Compression, TileType, zxy_to_tileid as lib_tileid  # type: ignore
    except ImportError:
        return None

    tiles, detected_ext = scan_tile_dir(tiles_dir, None)
    if not tiles:
        return None
    tile_type = {
        "png": TileType.PNG, "jpg": TileType.JPEG, "jpeg": TileType.JPEG,
        "webp": TileType.WEBP, "avif": TileType.AVIF, "pbf": TileType.MVT,
        "mvt": TileType.MVT,
    }.get(detected_ext, TileType.UNKNOWN)
    zooms = sorted({t[0] for t in tiles})
    bounds = summary_hint.get("bounds") or list(bounds_from_tiles(tiles))
    directory = os.path.dirname(os.path.abspath(out_path))
    if directory:
        os.makedirs(directory, exist_ok=True)

    with open(out_path, "wb") as handle:
        writer = Writer(handle)
        for z, x, y, path in tiles:
            with open(path, "rb") as tile_handle:
                writer.write_tile(lib_tileid(z, x, y), tile_handle.read())
        header = {
            "tile_type": tile_type,
            "tile_compression": Compression.NONE,
            "min_zoom": min(zooms),
            "max_zoom": max(zooms),
            "min_lon_e7": int(bounds[0] * 1e7),
            "min_lat_e7": int(bounds[1] * 1e7),
            "max_lon_e7": int(bounds[2] * 1e7),
            "max_lat_e7": int(bounds[3] * 1e7),
            "center_zoom": min(zooms),
            "center_lon_e7": int((bounds[0] + bounds[2]) / 2 * 1e7),
            "center_lat_e7": int((bounds[1] + bounds[3]) / 2 * 1e7),
        }
        writer.finalize(header, {
            "name": summary_hint.get("title", ""),
            "attribution": summary_hint.get("attribution", ""),
        })
    summary = dict(summary_hint)
    summary["path"] = out_path
    summary["bytes"] = os.path.getsize(out_path)
    return summary


def layers_json_snippet(summary: Dict[str, object], layer_id: str,
                        opacity: float = 0.7, phone_dir: str = "layers") -> Dict[str, object]:
    """Vytvoří řádek do ``layers.json`` v úložišti aplikace (viz PLAN.md sekce 4)."""
    return {
        "id": layer_id,
        "title": summary.get("title", layer_id),
        "type": "pmtiles-raster",
        "path": "%s/%s" % (phone_dir.rstrip("/"), os.path.basename(str(summary["path"]))),
        "format": summary.get("format", "png"),
        "minzoom": summary.get("minzoom", 0),
        "maxzoom": summary.get("maxzoom", 16),
        "bounds": summary.get("bounds", []),
        "opacity": opacity,
        "visible": False,
        "attribution": summary.get("attribution", ""),
    }


def human_size(num_bytes: float) -> str:
    """Bajty -> čitelný řetězec."""
    for unit in ("B", "kB", "MB", "GB"):
        if abs(num_bytes) < 1024.0:
            return "%.1f %s" % (num_bytes, unit)
        num_bytes /= 1024.0
    return "%.1f TB" % num_bytes


def main(argv: Optional[List[str]] = None) -> int:
    """Postaví PMTiles v3 archiv z adresáře dlaždic a vypíše snippet do layers.json."""
    parser = argparse.ArgumentParser(
        description="Adresář {z}/{x}/{y} -> PMTiles v3 (F1-4).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--tiles", required=True, help="vstupní adresář s dlaždicemi")
    parser.add_argument("--out", required=True, help="výstupní .pmtiles soubor")
    parser.add_argument("--name", default="", help="název vrstvy do metadat")
    parser.add_argument("--layer-id", default="", help="id vrstvy do layers.json (výchozí: název souboru)")
    parser.add_argument("--attribution", default="", help="atribuce (výchozí: z _meta.json)")
    parser.add_argument("--ext", help="vynutit příponu dlaždic (png/jpg/webp/pbf)")
    parser.add_argument("--opacity", type=float, default=0.7, help="výchozí průhlednost v layers.json")
    parser.add_argument("--no-dedup", action="store_true", help="vypnout deduplikaci podle obsahu")
    parser.add_argument("--prefer-lib", action="store_true",
                        help="použít balíček 'pmtiles', pokud je nainstalovaný")
    parser.add_argument("--verify", action="store_true",
                        help="po zápisu archiv znovu načíst a ověřit náhodné dlaždice")
    parser.add_argument("--quiet", action="store_true", help="bez progress baru")
    args = parser.parse_args(argv)

    try:
        summary = build_from_directory(
            args.tiles, args.out, name=args.name, attribution=args.attribution,
            ext=args.ext, dedup=not args.no_dedup, quiet=args.quiet,
        )
    except ValueError as exc:
        print("CHYBA: %s" % exc, file=sys.stderr)
        return 2

    used_lib = False
    if args.prefer_lib:
        lib_summary = build_with_pmtiles_package(args.tiles, args.out, summary)
        if lib_summary is not None:
            summary = lib_summary
            used_lib = True
            print("Použit nainstalovaný balíček 'pmtiles' (referenční implementace).")
        else:
            print("Balíček 'pmtiles' není k dispozici — použit vestavěný writer.")
    if not used_lib:
        print("Použit vestavěný PMTiles v3 writer (bez externích závislostí).")

    print("Archiv:   %s (%s)" % (summary["path"], human_size(float(summary["bytes"]))))
    print("Dlaždic:  %d (přeskočeno %d), položek v adresáři %d, unikátních obsahů %d"
          % (summary["tiles"], summary["skipped"], summary["entries"], summary["unique"]))
    saved = int(summary["tiles"]) - int(summary["unique"])
    if saved > 0:
        print("Deduplikace ušetřila %d dlaždic (%.1f %%)."
              % (saved, 100.0 * saved / max(1, int(summary["tiles"]))))
    print("Zoomy:    %s-%s" % (summary["minzoom"], summary["maxzoom"]))
    print("bbox:     %s" % ", ".join("%.5f" % v for v in summary["bounds"]))

    if args.verify:
        reader = PMTilesReader.from_file(str(summary["path"]))
        tiles, _ext = scan_tile_dir(args.tiles, args.ext)
        sample = tiles[:: max(1, len(tiles) // 20)][:20]
        bad = 0
        for z, x, y, path in sample:
            with open(path, "rb") as handle:
                expected = handle.read()
            if reader.get_tile(z, x, y) != expected:
                bad += 1
                print("  ! dlaždice %d/%d/%d se neshoduje" % (z, x, y), file=sys.stderr)
        print("Ověření: %d/%d vzorků OK" % (len(sample) - bad, len(sample)))
        if bad:
            return 1

    layer_id = args.layer_id or os.path.splitext(os.path.basename(str(summary["path"])))[0]
    snippet = layers_json_snippet(summary, layer_id, opacity=args.opacity)
    print("\n--- snippet do layers.json (Android/data/cz.hh.detektormapy/files/layers.json) ---")
    print(json.dumps(snippet, indent=2, ensure_ascii=False))
    if LICENCE_WARNING:
        print()
        print(LICENCE_WARNING)
    return 0


if __name__ == "__main__":
    sys.exit(main())
