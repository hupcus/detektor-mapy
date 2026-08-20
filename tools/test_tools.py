#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Testy desktop pipeline — běží BEZ sítě a BEZ GDAL.

Spuštění::

    python3 -m unittest discover -s tools -p 'test_*.py' -v
    python3 -m pytest tools/test_tools.py        # pokud je pytest

Pokryto: tile math (lonlat <-> dlaždice, bbox v EPSG:3857), varint,
Hilbertovo pořadí, serializace adresářů PMTiles, writer + vlastní reader
(round-trip bajtů dlaždic, magic, deduplikace) a parsování GCP JSON z aplikace.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import archiv_fetch as af  # noqa: E402
import build_pmtiles as bp  # noqa: E402
import check_endpoints as ce  # noqa: E402
import fetch_tiles as ft  # noqa: E402
import sources as src  # noqa: E402
import warp_scan as ws  # noqa: E402


# ===========================================================================
# Registr zdrojů
# ===========================================================================

class TestSources(unittest.TestCase):
    def test_ids_are_unique(self) -> None:
        ids = [s.id for s in src.all_sources()]
        self.assertEqual(len(ids), len(set(ids)))

    def test_known_types_and_zooms(self) -> None:
        for source in src.all_sources():
            self.assertIn(source.type, src.SOURCE_TYPES, source.id)
            self.assertLessEqual(source.min_zoom, source.max_zoom, source.id)
            self.assertTrue(source.url.startswith("http"), source.id)

    def test_get_source_raises_with_hint(self) -> None:
        with self.assertRaises(KeyError) as ctx:
            src.get_source("neexistuje")
        self.assertIn("ii_vm", str(ctx.exception))

    def test_p1_gate_skips_unverified_sources(self) -> None:
        """Neověřený P1 zdroj nesmí shodit CI; ověřený tam naopak patřit musí."""
        p1_ids = [s.id for s in src.p1_sources()]
        self.assertIn("ii_vm", p1_ids)

        # uan_npu byl ověřen 2026-08-20 (geoportal.npu.cz/.../CP_UAN/MapServer),
        # takže od té doby do brány patří.
        self.assertIn("uan_npu", p1_ids)

        # Invariant, který má test hlídat: cokoliv s verified=False je z brány venku,
        # ale s include_unverified=True se to objeví.
        gated = set(p1_ids)
        for source in src.all_sources():
            if source.priority == "P1" and not source.verified:
                self.assertNotIn(source.id, gated, source.id)
                self.assertIn(
                    source.id,
                    [s.id for s in src.p1_sources(include_unverified=True)],
                    source.id,
                )

    def test_uan_endpoint_is_the_verified_one(self) -> None:
        """Regrese: první odhad gis.up.npu.cz neexistoval, DNS na něj selhává."""
        source = src.get_source("uan_npu")
        self.assertIn("geoportal.npu.cz", source.url)
        self.assertNotIn("gis.up.npu.cz", source.url)
        self.assertTrue(source.verified)
        self.assertEqual(source.crs, "EPSG:5514")

    def test_capabilities_url_per_type(self) -> None:
        self.assertIn("REQUEST=GetCapabilities", src.get_source("ii_vm").get_capabilities_url())
        self.assertIn("SERVICE=WMS", src.get_source("dmr5g").get_capabilities_url())
        self.assertIn("f=json", src.get_source("uan_npu").get_capabilities_url())

    def test_cisarske_kvk_endpoint_is_the_verified_one(self) -> None:
        """Regrese: služba žije na geo-ags.kr-karlovarsky.cz (ověřeno 2026-08-20 exportem
        s bboxSR=3857&imageSR=3857), NE na gis.kr-karlovarsky.cz, který resetuje spojení."""
        source = src.get_source("cisarske_kvk")
        self.assertIn("geo-ags.kr-karlovarsky.cz", source.url)
        self.assertIn("/Image/CisarskeOtisky/MapServer", source.url)
        self.assertTrue(source.verified)
        self.assertEqual(source.crs, "EPSG:5514")
        self.assertEqual(source.type, "arcgis-rest")

    def test_chartae_sources_are_plain_xyz_in_web_mercator(self) -> None:
        """Regrese: chartae-antiquae má v cestě 'TMS', ale osa Y se NEpřevrací.

        Ověřeno 2026-08-20: standardní XYZ dlaždice vrací obraz, y-flip vrací
        prázdnou 334B výplň. Kdyby někdo typ přepnul zpět na wmts, check_endpoints
        by se ptal na neexistující GetCapabilities.
        """
        for source_id in ("muller_cechy", "muller_morava", "vm1_chartae", "vm2_chartae", "vm3_topo_chartae"):
            source = src.get_source(source_id)
            self.assertEqual(source.type, "xyz", source_id)
            self.assertEqual(source.crs, "EPSG:3857", source_id)
            self.assertIn("chartae-antiquae.cz/TMS/", source.url, source_id)
            self.assertIn("{z}", source.tile_template, source_id)
            # Probe musí být konkrétní ověřená dlaždice, ne šablona s {z}.
            self.assertNotIn("{", source.get_capabilities_url(), source_id)

    def test_vm1_chartae_max_zoom_exceeds_muller(self) -> None:
        """Military1 má data až do z15, Müllerovy mapy končí na z14 (z15 = prázdná PNG)."""
        self.assertEqual(src.get_source("vm1_chartae").max_zoom, 15)
        self.assertEqual(src.get_source("muller_cechy").max_zoom, 14)

    def test_cuzk_wm_caches_use_row_before_column(self) -> None:
        """Regrese: ArcGIS tile cache je /tile/{z}/{y}/{x} — prohození os vrací 404/mimo."""
        for source_id in ("ortofoto_wm", "ztm_wm"):
            template = src.get_source(source_id).tile_template or ""
            self.assertIn("/tile/{z}/{y}/{x}", template, source_id)
            self.assertEqual(src.get_source(source_id).crs, "EPSG:3857", source_id)

    def test_xyz_capabilities_url_falls_back_to_probe_tile(self) -> None:
        """Bez explicitní capabilities_url se ze šablony dosadí dlaždice uprostřed ČR."""
        probe = src.Source(
            id="_xyz_test",
            title="",
            type="xyz",
            url="https://example.test/T/{z}/{x}/{y}",
            min_zoom=5,
        ).get_capabilities_url()
        self.assertNotIn("{", probe)
        self.assertIn("/8/", probe)  # max(min_zoom, 8)

    def test_xyz_auto_mode_downloads_via_template(self) -> None:
        self.assertEqual(
            ft.TileFetcher._auto_mode(src.get_source("muller_cechy")),
            "wmts",
        )

    def test_archiv_token_parsing_matches_gp_message_format(self) -> None:
        """GP job vrací token ve zprávě 'Token je: …' (formát veřejné aplikace Archiv)."""
        messages = [
            {"description": "Executing..."},
            {"description": "Token je: abc123DEF=="},
            {"description": "Expiration je: 60"},
        ]
        self.assertEqual(af.parse_token_from_messages(messages), "abc123DEF==")
        self.assertIsNone(af.parse_token_from_messages([{"description": "nic"}]))

    def test_archiv_raster_prefix_per_series(self) -> None:
        """Ověřený katastr Úpice: cio=B2_a_6C_8260-1, om=B2_a_4C_8003, kme=B2_a_14C_8260-1."""
        attrs = {
            "cio_SIGN_INV": "B2_a_6C_8260-1",
            "om_SIGN_INV": "B2_a_4C_8003",
            "kme_SIGN_NOMEN": "B2_a_14C_8260-1",
        }
        self.assertEqual(af.raster_prefix(attrs, "cio"), "B2_a_6C_8260-1")
        self.assertEqual(af.raster_prefix(attrs, "om"), "B2_a_4C_8003")
        self.assertEqual(af.raster_prefix(attrs, "kme"), "B2_a_14C_8260-1")
        with self.assertRaises(ValueError):
            af.raster_prefix(attrs, "neznama")
        with self.assertRaises(ValueError):
            af.raster_prefix({"cio_SIGN_INV": ""}, "cio")

    def test_archiv_strip_plan_covers_whole_sheet(self) -> None:
        """Pruhy musí bezešvě pokrýt celý sken a respektovat limit výšky exportu."""
        # Úpice list 1 (oid 123526): rám 0,0,23,29 -> 11684x14732 px.
        bbox = (0.0, 0.0, 23.0, 29.0)
        width_px, height_px = af.frame_to_pixels(bbox)
        self.assertEqual((width_px, height_px), (11684, 14732))

        strips = af.plan_strips(bbox)
        self.assertEqual(len(strips), 4)  # ceil(14732 / 4100)
        # Odshora dolů, na sebe navazují, výšky dají celek.
        previous_bottom = bbox[3]
        total_px = 0
        for (x0, y0, x1, y1), (w, h) in strips:
            self.assertEqual((x0, x1), (bbox[0], bbox[2]))
            self.assertAlmostEqual(y1, previous_bottom)
            self.assertLessEqual(h, af.MAX_EXPORT_H)
            self.assertEqual(w, width_px)
            previous_bottom = y0
            total_px += h
        self.assertEqual(total_px, height_px)
        self.assertAlmostEqual(previous_bottom, bbox[1], places=6)

        # Sken širší než limit exportu chce svislé řezy, které neumíme -> chyba.
        with self.assertRaises(ValueError):
            af.plan_strips((0.0, 0.0, 40.0, 2.0))

    def test_archiv_fit_size_only_shrinks(self) -> None:
        self.assertEqual(af.fit_size(11684, 14732), (3251, 4100))
        self.assertEqual(af.fit_size(800, 600), (800, 600))

    def test_archiv_where_escapes_quotes(self) -> None:
        where = af.build_where_for_katastr("Ú'pice")
        self.assertIn("''", where)
        self.assertNotIn("'Ú'pice'", where)

    def test_classify_body_xyz_accepts_real_tile_rejects_empty(self) -> None:
        """Empty-tile heuristika: skutečný JPEG projde, 334B průhledná PNG ne."""
        source = src.get_source("muller_cechy")
        jpeg = b"\xff\xd8\xff\xe0" + b"\x00" * 500
        status, _ = ce._classify_body(source, jpeg, "image/jpeg")
        self.assertEqual(status, ce.STATUS_OK)

        empty_png = b"\x89PNG\r\n\x1a\n" + b"\x00" * 300  # ~334 B prázdná dlaždice
        status, detail = ce._classify_body(source, empty_png, "image/png")
        self.assertEqual(status, ce.STATUS_BAD_BODY)
        self.assertIn("prázdná", detail)

        status, _ = ce._classify_body(source, b"<html>error</html>", "text/html")
        self.assertEqual(status, ce.STATUS_BAD_BODY)

    def test_env_override(self) -> None:
        source = src.get_source("uan_npu")
        os.environ["DETEKTORMAPY_UAN_URL"] = "https://example.invalid/FeatureServer/9"
        try:
            self.assertEqual(source.effective_url, "https://example.invalid/FeatureServer/9")
        finally:
            del os.environ["DETEKTORMAPY_UAN_URL"]
        self.assertEqual(source.effective_url, source.url)


# ===========================================================================
# Tile math
# ===========================================================================

class TestTileMath(unittest.TestCase):
    def test_zoom0_is_single_tile(self) -> None:
        self.assertEqual(ft.lonlat_to_tile(0.0, 0.0, 0), (0, 0))
        self.assertEqual(ft.lonlat_to_tile(179.9, 84.0, 0), (0, 0))
        self.assertEqual(ft.lonlat_to_tile(-179.9, -84.0, 0), (0, 0))

    def test_zoom1_quadrants(self) -> None:
        self.assertEqual(ft.lonlat_to_tile(-90.0, 45.0, 1), (0, 0))   # SZ
        self.assertEqual(ft.lonlat_to_tile(90.0, 45.0, 1), (1, 0))    # SV
        self.assertEqual(ft.lonlat_to_tile(-90.0, -45.0, 1), (0, 1))  # JZ
        self.assertEqual(ft.lonlat_to_tile(90.0, -45.0, 1), (1, 1))   # JV

    def test_known_tile_prague(self) -> None:
        # Praha (14.4378, 50.0755) na z=12 -> dlaždice 2212/1387 v XYZ (slippy map) schématu
        self.assertEqual(ft.lonlat_to_tile(14.4378, 50.0755, 12), (2212, 1387))
        # kontrola i na jiném zoomu (z=15 = 8x jemnější mřížka)
        self.assertEqual(ft.lonlat_to_tile(14.4378, 50.0755, 15), (17698, 11102))

    def test_roundtrip_tile_to_lonlat(self) -> None:
        for zoom in (0, 5, 12, 18):
            for lon, lat in ((14.4378, 50.0755), (-73.98, 40.75), (0.0, 0.0), (150.0, -33.86)):
                x, y = ft.lonlat_to_tile(lon, lat, zoom)
                west, south, east, north = ft.tile_bounds_wgs84(x, y, zoom)
                self.assertLessEqual(west, lon + 1e-9)
                self.assertGreaterEqual(east, lon - 1e-9)
                self.assertLessEqual(south, lat + 1e-9)
                self.assertGreaterEqual(north, lat - 1e-9)

    def test_tile_bounds_3857_zoom0(self) -> None:
        minx, miny, maxx, maxy = ft.tile_bounds_3857(0, 0, 0)
        self.assertAlmostEqual(minx, -ft.ORIGIN_SHIFT, places=6)
        self.assertAlmostEqual(maxx, ft.ORIGIN_SHIFT, places=6)
        self.assertAlmostEqual(miny, -ft.ORIGIN_SHIFT, places=6)
        self.assertAlmostEqual(maxy, ft.ORIGIN_SHIFT, places=6)

    def test_tile_bounds_3857_matches_wgs84(self) -> None:
        zoom, x, y = 12, 2212, 1387
        west, south, east, north = ft.tile_bounds_wgs84(x, y, zoom)
        minx, miny, maxx, maxy = ft.tile_bounds_3857(x, y, zoom)
        expected_min = ft.lonlat_to_meters(west, south)
        expected_max = ft.lonlat_to_meters(east, north)
        self.assertAlmostEqual(minx, expected_min[0], places=3)
        self.assertAlmostEqual(miny, expected_min[1], places=3)
        self.assertAlmostEqual(maxx, expected_max[0], places=3)
        self.assertAlmostEqual(maxy, expected_max[1], places=3)

    def test_tile_3857_is_square(self) -> None:
        for zoom in (1, 8, 15):
            minx, miny, maxx, maxy = ft.tile_bounds_3857(3, 5, zoom)
            self.assertAlmostEqual(maxx - minx, maxy - miny, places=6)

    def test_meters_roundtrip(self) -> None:
        for lon, lat in ((14.4378, 50.0755), (0.0, 0.0), (-120.0, -60.0)):
            mx, my = ft.lonlat_to_meters(lon, lat)
            back_lon, back_lat = ft.meters_to_lonlat(mx, my)
            self.assertAlmostEqual(lon, back_lon, places=9)
            self.assertAlmostEqual(lat, back_lat, places=7)

    def test_clamp_lat(self) -> None:
        self.assertAlmostEqual(ft.clamp_lat(90.0), 85.05112877980659)
        self.assertAlmostEqual(ft.clamp_lat(-90.0), -85.05112877980659)
        self.assertAlmostEqual(ft.clamp_lat(10.0), 10.0)

    def test_tile_range_and_count(self) -> None:
        bbox = (14.45, 49.20, 15.10, 49.60)  # okres Tábor (zhruba)
        xmin, ymin, xmax, ymax = ft.tile_range(bbox, 12)
        self.assertLessEqual(xmin, xmax)
        self.assertLessEqual(ymin, ymax)
        tiles = list(ft.tiles_in_bbox(bbox, 12))
        self.assertEqual(len(tiles), (xmax - xmin + 1) * (ymax - ymin + 1))
        self.assertEqual(ft.count_tiles(bbox, [12]), len(tiles))
        # každý další zoom zhruba zečtyřnásobí počet
        self.assertGreater(ft.count_tiles(bbox, [13]), 3 * len(tiles))

    def test_tiles_in_bbox_cover_corners(self) -> None:
        bbox = (14.45, 49.20, 15.10, 49.60)
        tiles = set((x, y) for _z, x, y in ft.tiles_in_bbox(bbox, 13))
        self.assertIn(ft.lonlat_to_tile(bbox[0], bbox[3], 13), tiles)
        self.assertIn(ft.lonlat_to_tile(bbox[2], bbox[1], 13), tiles)

    def test_parse_zoom_range(self) -> None:
        self.assertEqual(ft.parse_zoom_range("12-16"), [12, 13, 14, 15, 16])
        self.assertEqual(ft.parse_zoom_range("14"), [14])
        self.assertEqual(ft.parse_zoom_range("10,12,14"), [10, 12, 14])
        self.assertEqual(ft.parse_zoom_range("16-12"), [12, 13, 14, 15, 16])
        with self.assertRaises(ValueError):
            ft.parse_zoom_range("30")
        with self.assertRaises(ValueError):
            ft.parse_zoom_range("")


class TestUrlBuilders(unittest.TestCase):
    def test_wmts_rest_template(self) -> None:
        source = src.get_source("ii_vm")
        url = ft.wmts_rest_url(source, 12, 2212, 1391)
        self.assertIn("/12/1391/2212.png", url)  # WMTS REST je {z}/{y}/{x}
        self.assertIn(source.layer, url)

    def test_wmts_kvp(self) -> None:
        source = src.get_source("ii_vm")
        url = ft.wmts_kvp_url(source, 12, 2212, 1391)
        self.assertIn("REQUEST=GetTile", url)
        self.assertIn("TILEMATRIX=12", url)
        self.assertIn("TILEROW=1391", url)
        self.assertIn("TILECOL=2212", url)

    def test_wms_getmap_uses_tile_bbox(self) -> None:
        source = src.get_source("dmr5g")
        bbox = ft.tile_bounds_3857(2212, 1391, 12)
        url = ft.wms_getmap_url(source, bbox, "EPSG:3857")
        self.assertIn("CRS=EPSG:3857", url)
        self.assertIn("WIDTH=256", url)
        self.assertIn("%.9f" % bbox[0], url)

    def test_wms_axis_order_swap_for_4326(self) -> None:
        swapped = ft._wms_bbox_string("EPSG:4326", "1.3.0", (1.0, 2.0, 3.0, 4.0))
        self.assertTrue(swapped.startswith("2.000000000,1.000000000"))
        plain = ft._wms_bbox_string("EPSG:4326", "1.1.1", (1.0, 2.0, 3.0, 4.0))
        self.assertTrue(plain.startswith("1.000000000,2.000000000"))
        # Krovak East North má pořadí E,N -> neprohazuje se
        krovak = ft._wms_bbox_string("EPSG:5514", "1.3.0", (1.0, 2.0, 3.0, 4.0))
        self.assertTrue(krovak.startswith("1.000000000,2.000000000"))

    def test_wmts_rest_without_template_raises(self) -> None:
        source = src.get_source("dmr5g")  # WMS zdroj, šablonu nemá
        with self.assertRaises(ValueError):
            ft.wmts_rest_url(source, 1, 0, 0)

    def test_arcgis_export_url(self) -> None:
        source = src.get_source("cisarske_jck")
        url = ft.arcgis_export_url(source, ft.tile_bounds_3857(1, 1, 2))
        self.assertIn("/export?", url)
        self.assertIn("bboxSR=3857", url)
        self.assertIn("f=image", url)

    def test_looks_like_image(self) -> None:
        self.assertTrue(ft._looks_like_image(b"\x89PNG\r\n\x1a\n" + b"\x00" * 8))
        self.assertTrue(ft._looks_like_image(b"\xff\xd8\xff\xe0" + b"\x00" * 8))
        self.assertFalse(ft._looks_like_image(b"<?xml version='1.0'?><ServiceException/>"))


# ===========================================================================
# Varint
# ===========================================================================

class TestVarint(unittest.TestCase):
    def test_small_values_single_byte(self) -> None:
        self.assertEqual(bp.write_varint(0), b"\x00")
        self.assertEqual(bp.write_varint(1), b"\x01")
        self.assertEqual(bp.write_varint(127), b"\x7f")

    def test_multi_byte_encoding(self) -> None:
        self.assertEqual(bp.write_varint(128), b"\x80\x01")
        self.assertEqual(bp.write_varint(300), b"\xac\x02")
        self.assertEqual(bp.write_varint(16384), b"\x80\x80\x01")

    def test_roundtrip(self) -> None:
        values = [0, 1, 2, 127, 128, 255, 300, 16383, 16384, 2 ** 31, 2 ** 53 - 1]
        buffer = b"".join(bp.write_varint(v) for v in values)
        pos = 0
        decoded = []
        for _ in values:
            value, pos = bp.read_varint(buffer, pos)
            decoded.append(value)
        self.assertEqual(decoded, values)
        self.assertEqual(pos, len(buffer))

    def test_negative_rejected(self) -> None:
        with self.assertRaises(ValueError):
            bp.write_varint(-1)

    def test_truncated_buffer_raises(self) -> None:
        with self.assertRaises(ValueError):
            bp.read_varint(b"\x80", 0)


# ===========================================================================
# Hilbertovo pořadí
# ===========================================================================

class TestHilbert(unittest.TestCase):
    def test_zoom0(self) -> None:
        self.assertEqual(bp.zxy_to_tileid(0, 0, 0), 0)
        self.assertEqual(bp.tileid_to_zxy(0), (0, 0, 0))

    def test_zoom1_order(self) -> None:
        # PMTiles v3: z1 začíná na id 1 a jde po Hilbertově křivce
        self.assertEqual(bp.zxy_to_tileid(1, 0, 0), 1)
        self.assertEqual(bp.zxy_to_tileid(1, 0, 1), 2)
        self.assertEqual(bp.zxy_to_tileid(1, 1, 1), 3)
        self.assertEqual(bp.zxy_to_tileid(1, 1, 0), 4)
        self.assertEqual(bp.zxy_to_tileid(2, 0, 0), 5)

    def test_roundtrip_all_low_zooms(self) -> None:
        for zoom in range(0, 6):
            seen = set()
            for x in range(1 << zoom):
                for y in range(1 << zoom):
                    tile_id = bp.zxy_to_tileid(zoom, x, y)
                    self.assertNotIn(tile_id, seen)
                    seen.add(tile_id)
                    self.assertEqual(bp.tileid_to_zxy(tile_id), (zoom, x, y))
            # id na daném zoomu tvoří souvislý blok
            self.assertEqual(max(seen) - min(seen) + 1, 4 ** zoom)

    def test_roundtrip_high_zoom_samples(self) -> None:
        for zoom, x, y in ((12, 2212, 1387), (14, 8849, 5566), (18, 141000, 89000)):
            self.assertEqual(bp.tileid_to_zxy(bp.zxy_to_tileid(zoom, x, y)), (zoom, x, y))

    def test_locality(self) -> None:
        # sousední dlaždice mají blízká id — to je smysl Hilbertova řazení
        base = bp.zxy_to_tileid(12, 2212, 1387)
        neighbour = bp.zxy_to_tileid(12, 2213, 1387)
        self.assertLess(abs(base - neighbour), 64)

    def test_out_of_range_rejected(self) -> None:
        with self.assertRaises(ValueError):
            bp.zxy_to_tileid(1, 2, 0)
        with self.assertRaises(ValueError):
            bp.zxy_to_tileid(-1, 0, 0)
        with self.assertRaises(ValueError):
            bp.tileid_to_zxy(-5)


# ===========================================================================
# Adresáře PMTiles
# ===========================================================================

class TestDirectory(unittest.TestCase):
    def test_empty_directory(self) -> None:
        self.assertEqual(bp.deserialize_directory(bp.serialize_directory([])), [])

    def test_roundtrip(self) -> None:
        entries = [
            bp.Entry(0, 0, 100, 1),
            bp.Entry(1, 100, 50, 2),
            bp.Entry(3, 150, 70, 1),
            bp.Entry(500, 999, 10, 1),
        ]
        decoded = bp.deserialize_directory(bp.serialize_directory(entries))
        self.assertEqual(decoded, entries)

    def test_consecutive_offsets_are_compressed(self) -> None:
        # Navazující položky mají offset zakódovaný jako 0 -> kratší výstup.
        contiguous = [bp.Entry(i, i * 10, 10, 1) for i in range(50)]
        scattered = [bp.Entry(i, (49 - i) * 10, 10, 1) for i in range(50)]
        self.assertLess(len(bp.serialize_directory(contiguous)),
                        len(bp.serialize_directory(scattered)))

    def test_optimize_directories_root_only(self) -> None:
        entries = [bp.Entry(i, i * 10, 10, 1) for i in range(100)]
        root, leaves, count = bp.optimize_directories(entries, bp.COMPRESSION_GZIP)
        self.assertEqual(leaves, b"")
        self.assertEqual(count, 0)
        self.assertLessEqual(len(root), bp.MAX_ROOT_DIR_BYTES)

    def test_optimize_directories_creates_leaves(self) -> None:
        # Hodně roztroušených položek se do 16 kB rootu nevejde -> vzniknou listy.
        entries = [bp.Entry(i * 7919, (i * 104729) % 10 ** 9, 4096 + i, 1)
                   for i in range(60000)]
        root, leaves, count = bp.optimize_directories(entries, bp.COMPRESSION_GZIP)
        self.assertLessEqual(len(root), bp.MAX_ROOT_DIR_BYTES)
        self.assertGreater(count, 0)
        self.assertGreater(len(leaves), 0)


# ===========================================================================
# Hlavička
# ===========================================================================

class TestHeader(unittest.TestCase):
    def test_size_and_magic(self) -> None:
        blob = bp.Header().serialize()
        self.assertEqual(len(blob), 127)
        self.assertEqual(blob[:7], b"PMTiles")
        self.assertEqual(blob[7], 3)

    def test_roundtrip(self) -> None:
        header = bp.Header()
        header.root_length = 321
        header.metadata_offset = 448
        header.metadata_length = 99
        header.data_offset = 1000
        header.data_length = 123456
        header.addressed_tiles = 77
        header.tile_type = bp.TILETYPE_JPEG
        header.min_zoom = 8
        header.max_zoom = 17
        header.min_lon_e7 = int(14.45 * 1e7)
        header.min_lat_e7 = int(49.20 * 1e7)
        header.max_lon_e7 = int(15.10 * 1e7)
        header.max_lat_e7 = int(49.60 * 1e7)
        decoded = bp.Header.deserialize(header.serialize())
        for attr in ("root_length", "metadata_offset", "metadata_length", "data_offset",
                     "data_length", "addressed_tiles", "tile_type", "min_zoom", "max_zoom",
                     "min_lon_e7", "min_lat_e7", "max_lon_e7", "max_lat_e7"):
            self.assertEqual(getattr(decoded, attr), getattr(header, attr), attr)

    def test_negative_coordinates(self) -> None:
        header = bp.Header()
        header.min_lon_e7 = -1234567890
        header.min_lat_e7 = -456789012
        decoded = bp.Header.deserialize(header.serialize())
        self.assertEqual(decoded.min_lon_e7, -1234567890)
        self.assertEqual(decoded.min_lat_e7, -456789012)

    def test_bad_magic_rejected(self) -> None:
        with self.assertRaises(ValueError):
            bp.Header.deserialize(b"NOTPMTI" + b"\x03" + b"\x00" * 119)

    def test_bad_version_rejected(self) -> None:
        blob = bytearray(bp.Header().serialize())
        blob[7] = 2
        with self.assertRaises(ValueError):
            bp.Header.deserialize(bytes(blob))


# ===========================================================================
# Writer + reader
# ===========================================================================

def _fake_png(seed: int) -> bytes:
    """Syntetická 'dlaždice' s validním PNG magic — pillow není potřeba."""
    return b"\x89PNG\r\n\x1a\n" + bytes([seed % 251]) * 64 + b"IEND"


class TestPMTilesWriter(unittest.TestCase):
    def setUp(self) -> None:
        self.tmpdir = tempfile.mkdtemp(prefix="dm_test_")

    def tearDown(self) -> None:
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _write_tile_dir(self, zooms=(12, 13), span=4) -> str:
        tiles_dir = os.path.join(self.tmpdir, "tiles")
        counter = 0
        for zoom in zooms:
            for x in range(1000, 1000 + span):
                for y in range(2000, 2000 + span):
                    path = os.path.join(tiles_dir, str(zoom), str(x))
                    os.makedirs(path, exist_ok=True)
                    with open(os.path.join(path, "%d.png" % y), "wb") as handle:
                        handle.write(_fake_png(counter))
                    counter += 1
        return tiles_dir

    def test_tiles_must_be_ordered(self) -> None:
        writer = bp.PMTilesWriter()
        writer.add_tile(10, b"a")
        with self.assertRaises(ValueError):
            writer.add_tile(5, b"b")

    def test_roundtrip_in_memory(self) -> None:
        writer = bp.PMTilesWriter(tile_type=bp.TILETYPE_PNG)
        expected = {}
        for zoom in (3, 4):
            for x in range(1 << zoom):
                for y in range(1 << zoom):
                    expected[(zoom, x, y)] = _fake_png(zoom * 1000 + x * 31 + y)
        ordered = sorted(expected, key=lambda k: bp.zxy_to_tileid(*k))
        for key in ordered:
            writer.add_zxy(key[0], key[1], key[2], expected[key])
        blob = writer.finalize({"name": "test"}, 3, 4, (14.0, 49.0, 15.0, 50.0))

        reader = bp.PMTilesReader(blob)
        self.assertEqual(blob[:7], b"PMTiles")
        self.assertEqual(reader.header.min_zoom, 3)
        self.assertEqual(reader.header.max_zoom, 4)
        self.assertTrue(reader.header.clustered)
        self.assertEqual(reader.metadata()["name"], "test")
        for key, data in expected.items():
            self.assertEqual(reader.get_tile(*key), data, "dlaždice %s" % (key,))
        self.assertIsNone(reader.get_tile(5, 0, 0))
        self.assertEqual(reader.header.addressed_tiles, len(expected))

    def test_hilbert_ordering_of_entries(self) -> None:
        tiles_dir = self._write_tile_dir()
        found, _ext = bp.scan_tile_dir(tiles_dir)
        ids = [bp.zxy_to_tileid(z, x, y) for z, x, y, _p in found]
        self.assertEqual(ids, sorted(ids))
        out = os.path.join(self.tmpdir, "a.pmtiles")
        bp.build_from_directory(tiles_dir, out, name="a", quiet=True)
        reader = bp.PMTilesReader.from_file(out)
        entry_ids = [e.tile_id for e in reader.root_entries()]
        self.assertEqual(entry_ids, sorted(entry_ids))
        self.assertEqual(sorted(reader.all_tile_ids()), sorted(ids))

    def test_deduplication(self) -> None:
        writer = bp.PMTilesWriter()
        same = _fake_png(1)
        for zoom, x, y in sorted(
            [(4, x, y) for x in range(4) for y in range(4)],
            key=lambda k: bp.zxy_to_tileid(*k),
        ):
            writer.add_zxy(zoom, x, y, same)
        self.assertEqual(writer.unique_contents, 1)
        self.assertEqual(writer.addressed_tiles, 16)
        blob = writer.finalize({}, 4, 4, (0.0, 0.0, 1.0, 1.0))
        reader = bp.PMTilesReader(blob)
        self.assertEqual(reader.header.tile_contents, 1)
        self.assertEqual(reader.header.addressed_tiles, 16)
        self.assertLess(reader.header.data_length, 200)  # jen jedna kopie dat
        for x in range(4):
            for y in range(4):
                self.assertEqual(reader.get_tile(4, x, y), same)

    def test_run_length_merges_consecutive(self) -> None:
        writer = bp.PMTilesWriter()
        same = b"same-bytes"
        for tile_id in range(100, 110):
            writer.add_tile(tile_id, same)
        self.assertEqual(len(writer.entries), 1)
        self.assertEqual(writer.entries[0].run_length, 10)

    def test_dedup_can_be_disabled(self) -> None:
        writer = bp.PMTilesWriter(dedup=False)
        same = _fake_png(7)
        for tile_id in range(5):
            writer.add_tile(tile_id, same)
        self.assertEqual(writer.unique_contents, 5)
        self.assertEqual(len(writer.entries), 5)

    def test_build_from_directory_and_verify(self) -> None:
        tiles_dir = self._write_tile_dir()
        with open(os.path.join(tiles_dir, "_meta.json"), "w", encoding="utf-8") as handle:
            json.dump({"title": "Testovací vrstva", "attribution": "© test",
                       "bbox_wgs84": [14.0, 49.0, 15.0, 50.0]}, handle)
        out = os.path.join(self.tmpdir, "b.pmtiles")
        summary = bp.build_from_directory(tiles_dir, out, quiet=True)
        self.assertEqual(summary["tiles"], 32)
        self.assertEqual(summary["skipped"], 0)
        self.assertEqual(summary["title"], "Testovací vrstva")
        self.assertEqual(summary["bounds"], [14.0, 49.0, 15.0, 50.0])
        self.assertTrue(os.path.exists(out))

        reader = bp.PMTilesReader.from_file(out)
        self.assertEqual(reader.metadata()["attribution"], "© test")
        for zoom, x, y, path in bp.scan_tile_dir(tiles_dir)[0]:
            with open(path, "rb") as handle:
                self.assertEqual(reader.get_tile(zoom, x, y), handle.read())

    def test_build_from_directory_ignores_junk(self) -> None:
        tiles_dir = self._write_tile_dir(zooms=(12,), span=2)
        junk = os.path.join(tiles_dir, "12", "1000")
        with open(os.path.join(junk, "2000.png.part"), "wb") as handle:
            handle.write(b"unfinished")
        with open(os.path.join(junk, "readme.txt"), "wb") as handle:
            handle.write(b"text")
        with open(os.path.join(junk, "2099.png"), "wb") as handle:
            handle.write(b"")  # prázdný soubor
        found, ext = bp.scan_tile_dir(tiles_dir)
        self.assertEqual(ext, "png")
        self.assertEqual(len(found), 4)

    def test_empty_directory_raises(self) -> None:
        empty = os.path.join(self.tmpdir, "empty")
        os.makedirs(empty)
        with self.assertRaises(ValueError):
            bp.build_from_directory(empty, os.path.join(self.tmpdir, "c.pmtiles"), quiet=True)

    def test_leaf_directories_are_readable(self) -> None:
        # Malý rozpočet na root vynutí listové adresáře i u menšího archivu.
        writer = bp.PMTilesWriter(max_root_bytes=512)
        zoom = 8
        keys = [(zoom, x, y) for x in range(40) for y in range(40)]
        keys.sort(key=lambda k: bp.zxy_to_tileid(*k))
        payloads = [b"x" * (10 + (index * 37) % 500) + b"#%d" % index
                    for index in range(len(keys))]
        for index, key in enumerate(keys):
            writer.add_zxy(key[0], key[1], key[2], payloads[index])
        blob = writer.finalize({}, zoom, zoom, (0.0, 0.0, 1.0, 1.0))
        reader = bp.PMTilesReader(blob)
        self.assertGreater(reader.header.leaf_length, 0, "měly vzniknout listové adresáře")
        self.assertLessEqual(reader.header.root_length, 512)
        for index in range(len(keys)):
            zoom_, x, y = keys[index]
            self.assertEqual(reader.get_tile(zoom_, x, y), payloads[index])
        self.assertIsNone(reader.get_tile(zoom, 200, 200))
        self.assertEqual(len(list(reader.all_tile_ids())), len(keys))

    def test_section_offsets_are_contiguous(self) -> None:
        writer = bp.PMTilesWriter()
        writer.add_zxy(1, 0, 0, _fake_png(1))
        blob = writer.finalize({"name": "x"}, 1, 1, (0.0, 0.0, 1.0, 1.0))
        header = bp.Header.deserialize(blob[:127])
        self.assertEqual(header.root_offset, 127)
        self.assertEqual(header.metadata_offset, header.root_offset + header.root_length)
        self.assertEqual(header.leaf_offset, header.metadata_offset + header.metadata_length)
        self.assertEqual(header.data_offset, header.leaf_offset + header.leaf_length)
        self.assertEqual(len(blob), header.data_offset + header.data_length)

    def test_layers_json_snippet(self) -> None:
        summary = {"path": "/tmp/ii_vm_tabor.pmtiles", "title": "II. VM", "format": "png",
                   "minzoom": 12, "maxzoom": 16, "bounds": [14.0, 49.0, 15.0, 50.0],
                   "attribution": "© CENIA"}
        snippet = bp.layers_json_snippet(summary, "ii_vm_tabor", opacity=0.55)
        self.assertEqual(snippet["id"], "ii_vm_tabor")
        self.assertEqual(snippet["path"], "layers/ii_vm_tabor.pmtiles")
        self.assertEqual(snippet["opacity"], 0.55)
        self.assertEqual(snippet["type"], "pmtiles-raster")
        json.dumps(snippet)  # musí být serializovatelné


class TestBoundsFromTiles(unittest.TestCase):
    def test_bounds_cover_tiles(self) -> None:
        tiles = [(12, 2212, 1387, ""), (12, 2214, 1389, ""), (13, 4424, 2774, "")]
        west, south, east, north = bp.bounds_from_tiles(tiles)
        self.assertLess(west, east)
        self.assertLess(south, north)
        tile_w, tile_s, tile_e, tile_n = ft.tile_bounds_wgs84(2212, 1387, 12)
        self.assertAlmostEqual(west, tile_w, places=9)
        self.assertAlmostEqual(north, tile_n, places=9)

    def test_empty_returns_world(self) -> None:
        self.assertEqual(bp.bounds_from_tiles([]), (-180.0, -85.0, 180.0, 85.0))


# ===========================================================================
# GCP JSON z aplikace
# ===========================================================================

VALID_GCP = {
    "image": "scan.jpg",
    "width": 5000,
    "height": 4000,
    "gcps": [
        {"px": 100.0, "py": 200.0, "lon": 14.66, "lat": 49.41},
        {"px": 4800.0, "py": 250.0, "lon": 14.75, "lat": 49.42},
        {"px": 2500.0, "py": 3800.0, "lon": 14.70, "lat": 49.35},
    ],
    "created": "2026-08-19T21:00:00Z",
}


class TestGcpParsing(unittest.TestCase):
    def test_valid(self) -> None:
        gcp = ws.parse_gcp_json(VALID_GCP)
        self.assertEqual(gcp.image, "scan.jpg")
        self.assertEqual(gcp.width, 5000)
        self.assertEqual(gcp.count, 3)
        self.assertEqual(gcp.created, "2026-08-19T21:00:00Z")
        self.assertEqual(gcp.recommended_method(), "polynomial")

    def test_six_points_recommend_tps(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        for i in range(3):
            data["gcps"].append({"px": 1000.0 + i, "py": 1000.0 + i,
                                 "lon": 14.68 + i * 0.001, "lat": 49.38 + i * 0.001})
        self.assertEqual(ws.parse_gcp_json(data).recommended_method(), "tps")

    def test_missing_key(self) -> None:
        for key in ("image", "width", "height", "gcps"):
            data = json.loads(json.dumps(VALID_GCP))
            del data[key]
            with self.assertRaises(ValueError, msg=key):
                ws.parse_gcp_json(data)

    def test_too_few_points(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        data["gcps"] = data["gcps"][:2]
        with self.assertRaises(ValueError):
            ws.parse_gcp_json(data)

    def test_pixel_outside_image(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        data["gcps"][0]["px"] = 99999.0
        with self.assertRaises(ValueError):
            ws.parse_gcp_json(data)

    def test_lonlat_out_of_range(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        data["gcps"][1]["lat"] = 123.0
        with self.assertRaises(ValueError):
            ws.parse_gcp_json(data)

    def test_non_numeric(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        data["gcps"][0]["lon"] = "čtrnáct"
        with self.assertRaises(ValueError):
            ws.parse_gcp_json(data)

    def test_bad_dimensions(self) -> None:
        data = json.loads(json.dumps(VALID_GCP))
        data["width"] = 0
        with self.assertRaises(ValueError):
            ws.parse_gcp_json(data)

    def test_load_from_file_and_relative_image(self) -> None:
        tmpdir = tempfile.mkdtemp(prefix="dm_gcp_")
        try:
            path = os.path.join(tmpdir, "gcp.json")
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(VALID_GCP, handle)
            gcp = ws.load_gcp_file(path)
            self.assertEqual(gcp.image_path(), os.path.join(tmpdir, "scan.jpg"))
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_load_invalid_json(self) -> None:
        tmpdir = tempfile.mkdtemp(prefix="dm_gcp_")
        try:
            path = os.path.join(tmpdir, "bad.json")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("{not json")
            with self.assertRaises(ValueError):
                ws.load_gcp_file(path)
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    def test_missing_file(self) -> None:
        with self.assertRaises(ValueError):
            ws.load_gcp_file("/neexistujici/cesta/gcp.json")


class TestWarpCommands(unittest.TestCase):
    def test_translate_cmd_has_all_gcps(self) -> None:
        gcp = ws.parse_gcp_json(VALID_GCP)
        cmd = ws.build_translate_cmd(gcp, "in.jpg", "out.tif")
        self.assertEqual(cmd[0], "gdal_translate")
        self.assertEqual(cmd.count("-gcp"), 3)
        self.assertIn("-a_srs", cmd)
        self.assertEqual(cmd[-2:], ["in.jpg", "out.tif"])

    def test_warp_cmd_tps(self) -> None:
        cmd = ws.build_warp_cmd("a.tif", "b.tif", method="tps")
        self.assertIn("-tps", cmd)
        self.assertNotIn("-order", cmd)
        self.assertIn("EPSG:3857", cmd)
        self.assertIn("lanczos", cmd)

    def test_warp_cmd_polynomial(self) -> None:
        cmd = ws.build_warp_cmd("a.tif", "b.tif", method="polynomial", order=2)
        self.assertNotIn("-tps", cmd)
        self.assertIn("-order", cmd)
        self.assertEqual(cmd[cmd.index("-order") + 1], "2")

    def test_gdal2tiles_cmd_is_xyz(self) -> None:
        cmd = ws.build_gdal2tiles_cmd("a.tif", "tiles", "12-16")
        self.assertIn("--xyz", cmd)
        self.assertIn("12-16", cmd)


# ===========================================================================
# Hillshade (numpy je volitelný — test se přeskočí, když chybí)
# ===========================================================================

try:
    import numpy as _np
except ImportError:  # pragma: no cover
    _np = None

import dmr5g_hillshade as hs  # noqa: E402


@unittest.skipIf(_np is None, "numpy není nainstalované")
class TestHillshade(unittest.TestCase):
    def test_flat_terrain_is_uniform(self) -> None:
        dtm = _np.zeros((20, 20), dtype="float32")
        shade = hs.multidirectional_hillshade(dtm, 1.0)
        self.assertAlmostEqual(float(shade.min()), float(shade.max()), places=9)
        self.assertTrue(0.0 <= float(shade.min()) <= 1.0)

    def test_linear_ramp_has_constant_slope(self) -> None:
        # Lineární rampa má všude stejný sklon i orientaci -> stín je konstantní.
        ramp = _np.tile(_np.arange(20, dtype="float32"), (20, 1))
        shade = hs.multidirectional_hillshade(ramp, 1.0)
        self.assertAlmostEqual(float(shade.max()) - float(shade.min()), 0.0, places=9)
        self.assertLess(float(shade.max()), 1.0)  # skloněný povrch != plocha

    def test_bump_creates_contrast(self) -> None:
        yy, xx = _np.mgrid[0:31, 0:31].astype("float64")
        bump = 10.0 * _np.exp(-(((xx - 15) ** 2 + (yy - 15) ** 2) / 40.0))
        shade = hs.multidirectional_hillshade(bump.astype("float32"), 1.0)
        self.assertGreater(float(shade.max()) - float(shade.min()), 0.0)
        self.assertLessEqual(float(shade.max()), 1.0)
        self.assertGreaterEqual(float(shade.min()), 0.0)

    def test_weights_validated(self) -> None:
        dtm = _np.zeros((5, 5), dtype="float32")
        with self.assertRaises(ValueError):
            hs.multidirectional_hillshade(dtm, 1.0, (0.0, 90.0), weights=(1.0,))

    def test_svf_range_and_pit(self) -> None:
        dtm = _np.zeros((21, 21), dtype="float32")
        svf_flat = hs.sky_view_factor(dtm, 1.0, directions=8, max_radius=5)
        self.assertTrue(bool((svf_flat <= 1.0001).all()))
        dtm[10, 10] = -5.0  # jáma -> méně viditelné oblohy
        svf_pit = hs.sky_view_factor(dtm, 1.0, directions=8, max_radius=5)
        self.assertLess(float(svf_pit[10, 10]), float(svf_flat[10, 10]))

    def test_composite_uint8(self) -> None:
        shade = _np.full((4, 4), 0.5)
        image = hs.composite(shade, None)
        self.assertEqual(image.dtype.name, "uint8")
        self.assertEqual(int(image[0, 0]), 127)
        blended = hs.composite(shade, _np.ones((4, 4)), svf_weight=0.5)
        self.assertGreater(int(blended[0, 0]), 127)

    def test_shift_keeps_shape(self) -> None:
        array = _np.arange(25, dtype="float64").reshape(5, 5)
        for dy, dx in ((1, 0), (0, -2), (-3, 3)):
            self.assertEqual(hs._shift(array, dy, dx).shape, array.shape)


class TestAtomParsing(unittest.TestCase):
    ATOM = b"""<?xml version="1.0" encoding="utf-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom" xmlns:georss="http://www.georss.org/georss">
      <entry>
        <title>DMR5G list TABO01</title>
        <georss:box>49.3 14.6 49.5 14.9</georss:box>
        <link rel="alternate" href="https://example.invalid/TABO01.zip" type="application/zip"/>
      </entry>
      <entry>
        <title>Jihocesky kraj</title>
        <link rel="alternate" href="https://example.invalid/jck.xml" type="application/atom+xml"/>
      </entry>
    </feed>"""

    def test_parse(self) -> None:
        parsed = hs.parse_atom(self.ATOM)
        self.assertEqual(len(parsed["files"]), 1)
        self.assertEqual(len(parsed["feeds"]), 1)
        self.assertEqual(parsed["files"][0]["bbox"], (14.6, 49.3, 14.9, 49.5))

    def test_bad_xml(self) -> None:
        with self.assertRaises(ValueError):
            hs.parse_atom(b"<feed><entry>")

    def test_bbox_intersects(self) -> None:
        self.assertTrue(hs.bbox_intersects((0, 0, 2, 2), (1, 1, 3, 3)))
        self.assertFalse(hs.bbox_intersects((0, 0, 1, 1), (2, 2, 3, 3)))


class TestNoNetworkOrGdalNeeded(unittest.TestCase):
    """Pojistka: testy nesmí potřebovat síť ani GDAL."""

    def test_gdal_detection_does_not_raise(self) -> None:
        self.assertIsInstance(ft.have_gdal(), bool)
        self.assertIsInstance(ws.missing_gdal_tools(), list)
        self.assertIsInstance(hs.missing_tools(["gdalinfo"]), list)

    def test_human_size(self) -> None:
        self.assertEqual(ft.human_size(512), "512.0 B")
        self.assertEqual(ft.human_size(1536), "1.5 kB")
        self.assertEqual(bp.human_size(1048576), "1.0 MB")


if __name__ == "__main__":
    unittest.main(verbosity=2)
