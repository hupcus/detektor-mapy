#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Ověří dostupnost všech datových zdrojů (issue F0-4).

Pro každý zdroj z ``tools/sources.py`` provede GetCapabilities (WMTS/WMS) nebo
REST metadata dotaz (ArcGIS ``?f=json``), případně stažení ATOM feedu, a vypíše
tabulku se stavem. Skript **nikdy nezůstane viset** — timeout je na každém
requestu i na celém běhu.

Návratový kód:
  0 = všechny ověřené P1 zdroje odpovídají
  1 = alespoň jeden ověřený P1 zdroj je nedostupný
  2 = chyba použití (např. neznámé --id, chybí requests)

Příklady::

    python3 tools/check_endpoints.py
    python3 tools/check_endpoints.py --json
    python3 tools/check_endpoints.py --id ii_vm --capabilities
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Dict, List, Optional, Tuple
from xml.etree import ElementTree

try:  # requests je jediná tvrdá závislost, ale chceme čitelnou hlášku
    import requests
except ImportError:  # pragma: no cover - závisí na prostředí
    requests = None  # type: ignore[assignment]

try:
    from sources import Source, all_sources, get_source
except ImportError:  # spuštěno mimo adresář tools/
    from tools.sources import Source, all_sources, get_source  # type: ignore

USER_AGENT = "DetektorMapy-check_endpoints/1.0 (osobni pouziti; +https://github.com/hupcus)"

STATUS_OK = "OK"
STATUS_TIMEOUT = "TIMEOUT"
STATUS_ERROR = "ERROR"
STATUS_BAD_BODY = "BAD_BODY"


class CheckResult(dict):
    """Výsledek jedné kontroly — dict kvůli snadnému ``--json``."""

    @property
    def ok(self) -> bool:
        return bool(self.get("status") == STATUS_OK)


def _classify_body(source: Source, body: bytes, content_type: str) -> Tuple[str, str]:
    """Zjistí, jestli tělo odpovědi vypadá jako očekávaná metadata.

    Vrací (status, detail). Služby často vrací HTTP 200 s chybovým XML/HTML,
    takže samotný stavový kód nestačí.
    """
    head = body[:4096]
    text = head.decode("utf-8", "replace")
    lowered = text.lower()

    if source.type == "arcgis-rest":
        try:
            parsed = json.loads(body.decode("utf-8", "replace"))
        except ValueError:
            return STATUS_BAD_BODY, "odpověď není JSON (content-type: %s)" % content_type
        if isinstance(parsed, dict) and "error" in parsed:
            err = parsed.get("error") or {}
            return STATUS_BAD_BODY, "ArcGIS error: %s" % err.get("message", err)
        name = ""
        if isinstance(parsed, dict):
            name = str(parsed.get("name") or parsed.get("mapName") or parsed.get("serviceDescription") or "")
        return STATUS_OK, ("vrstva: %s" % name[:60]) if name else "JSON metadata OK"

    if "serviceexception" in lowered or "exceptionreport" in lowered:
        match = re.search(r">([^<>]{5,120})<", text)
        return STATUS_BAD_BODY, "služba vrátila výjimku: %s" % (match.group(1).strip() if match else "?")

    if source.type in ("wmts", "arcgis-wmts"):
        if "capabilities" in lowered:
            return STATUS_OK, "WMTS Capabilities OK"
        return STATUS_BAD_BODY, "chybí <Capabilities> (content-type: %s)" % content_type

    if source.type == "wms":
        if "wms_capabilities" in lowered or "wmt_ms_capabilities" in lowered:
            return STATUS_OK, "WMS Capabilities OK"
        return STATUS_BAD_BODY, "chybí WMS_Capabilities (content-type: %s)" % content_type

    if source.type == "atom":
        if "<feed" in lowered or "atom" in lowered:
            return STATUS_OK, "ATOM feed OK"
        return STATUS_BAD_BODY, "nevypadá jako ATOM feed"

    if source.type == "xyz":
        # Probe je konkrétní dlaždice: musí to být obrázek a ne prázdná průhledná
        # výplň (ta má u ověřených služeb ~334 B; hranice 400 B viz DATA_SOURCES.md).
        is_image = body.startswith(b"\xff\xd8") or body.startswith(b"\x89PNG")
        if not is_image:
            return STATUS_BAD_BODY, "odpověď není obrázek (content-type: %s)" % content_type
        if len(body) <= 400:
            return STATUS_BAD_BODY, "prázdná dlaždice (%d B) — probe je mimo pokrytí?" % len(body)
        return STATUS_OK, "dlaždice OK (%d B)" % len(body)

    return STATUS_OK, "odpověď přijata"


def check_source(source: Source, timeout: float = 15.0, max_bytes: int = 1_000_000) -> CheckResult:
    """Provede jeden probe s tvrdým timeoutem; nikdy nevyhodí výjimku."""
    url = source.get_capabilities_url()
    started = time.time()
    result = CheckResult(
        id=source.id,
        title=source.title,
        type=source.type,
        priority=source.priority,
        verified=source.verified,
        url=url,
        status=STATUS_ERROR,
        http=None,
        elapsed_ms=0,
        detail="",
        body_head="",
    )
    if requests is None:
        result["detail"] = "chybí modul requests (pip install -r tools/requirements.txt)"
        return result

    try:
        response = requests.get(
            url,
            timeout=(min(timeout, 10.0), timeout),  # (connect, read)
            headers={"User-Agent": USER_AGENT, "Accept": "*/*"},
            stream=True,
            allow_redirects=True,
        )
        try:
            body = response.raw.read(max_bytes, decode_content=True) or b""
        except Exception:  # noqa: BLE001 - degradace na .content
            body = response.content[:max_bytes]
        result["http"] = response.status_code
        content_type = response.headers.get("Content-Type", "")
        if response.status_code >= 400:
            result["status"] = "HTTP %d" % response.status_code
            result["detail"] = response.reason or ""
        else:
            status, detail = _classify_body(source, body, content_type)
            result["status"] = status
            result["detail"] = detail
        result["body_head"] = body[:2000].decode("utf-8", "replace")
        response.close()
    except requests.exceptions.Timeout:
        result["status"] = STATUS_TIMEOUT
        result["detail"] = "timeout po %.0f s" % timeout
    except requests.exceptions.SSLError as exc:
        result["status"] = STATUS_ERROR
        result["detail"] = "TLS chyba: %s" % str(exc)[:120]
    except requests.exceptions.RequestException as exc:
        result["status"] = STATUS_ERROR
        result["detail"] = "%s: %s" % (type(exc).__name__, str(exc)[:120])
    except Exception as exc:  # noqa: BLE001 - probe nesmí shodit běh
        result["status"] = STATUS_ERROR
        result["detail"] = "neočekávaná chyba: %s" % str(exc)[:120]
    finally:
        result["elapsed_ms"] = int((time.time() - started) * 1000)
    return result


# ---------------------------------------------------------------------------
# Rozbor Capabilities — pomáhá zjistit skutečná jména LAYER / TILEMATRIXSET
# ---------------------------------------------------------------------------

def _localname(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def summarize_capabilities(xml_bytes: bytes, limit: int = 40) -> Dict[str, List[str]]:
    """Vytáhne z Capabilities jména vrstev a TileMatrixSetů (bez namespace magie)."""
    out = {"layers": [], "tilematrixsets": [], "formats": []}  # type: Dict[str, List[str]]
    try:
        root = ElementTree.fromstring(xml_bytes)
    except ElementTree.ParseError as exc:
        out["layers"].append("<nelze parsovat XML: %s>" % exc)
        return out
    for element in root.iter():
        name = _localname(element.tag)
        if name == "Layer":
            for child in element:
                if _localname(child.tag) in ("Identifier", "Name") and child.text:
                    out["layers"].append(child.text.strip())
                    break
        elif name == "TileMatrixSet":
            for child in element:
                if _localname(child.tag) == "Identifier" and child.text:
                    value = child.text.strip()
                    if value not in out["tilematrixsets"]:
                        out["tilematrixsets"].append(value)
                    break
        elif name == "Format" and element.text:
            value = element.text.strip()
            if value not in out["formats"]:
                out["formats"].append(value)
    for key in out:
        out[key] = out[key][:limit]
    return out


# ---------------------------------------------------------------------------
# Výstup
# ---------------------------------------------------------------------------

def render_table(results: List[CheckResult]) -> str:
    rows = [("ID", "PRIO", "STAV", "HTTP", "ms", "DETAIL")]
    for r in results:
        rows.append(
            (
                str(r["id"]),
                str(r["priority"]) + ("" if r["verified"] else "*"),
                str(r["status"]),
                str(r["http"] if r["http"] is not None else "-"),
                str(r["elapsed_ms"]),
                str(r["detail"])[:70],
            )
        )
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]))]
    lines = []
    for idx, row in enumerate(rows):
        lines.append("  ".join(row[i].ljust(widths[i]) for i in range(len(row))).rstrip())
        if idx == 0:
            lines.append("  ".join("-" * widths[i] for i in range(len(row))))
    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    """Ověří dostupnost datových zdrojů a vypíše tabulku stavů."""
    parser = argparse.ArgumentParser(
        description="Kontrola dostupnosti datových zdrojů DetektorMapy (F0-4).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--id", action="append", help="zkontrolovat jen tento zdroj (lze opakovat)")
    parser.add_argument("--timeout", type=float, default=15.0, help="timeout jednoho requestu v sekundách")
    parser.add_argument("--workers", type=int, default=4, help="počet paralelních dotazů")
    parser.add_argument("--json", action="store_true", help="strojově čitelný výstup pro CI")
    parser.add_argument(
        "--capabilities",
        action="store_true",
        help="vypsat nalezené vrstvy a TileMatrixSety z Capabilities (užitečné pro ověření LAYER)",
    )
    parser.add_argument(
        "--fail-on",
        choices=("p1", "any", "none"),
        default="p1",
        help="kdy vrátit exit kód 1 (p1 = jen ověřené P1 zdroje)",
    )
    args = parser.parse_args(argv)

    if requests is None:
        print(
            "CHYBA: chybí modul 'requests'. Nainstaluj: "
            "python3 -m pip install -r tools/requirements.txt",
            file=sys.stderr,
        )
        return 2

    try:
        if args.id:
            sources = [get_source(sid) for sid in args.id]
        else:
            sources = all_sources()
    except KeyError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    workers = max(1, min(args.workers, len(sources)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda s: check_source(s, args.timeout), sources))

    if args.json:
        payload = [{k: v for k, v in r.items() if k != "body_head"} for r in results]
        print(json.dumps(payload, indent=2, ensure_ascii=False))
    else:
        print(render_table(results))
        if args.capabilities:
            for source, result in zip(sources, results):
                print()
                print("=== %s — %s" % (source.id, result["url"]))
                if result["status"] != STATUS_OK:
                    print("  (přeskočeno, stav %s)" % result["status"])
                    continue
                if source.type == "arcgis-rest":
                    print("  %s" % str(result["body_head"])[:800])
                    continue
                if source.type == "xyz":
                    print("  (xyz šablona nemá Capabilities — probe je dlaždice, viz stav výše)")
                    continue
                summary = summarize_capabilities(str(result["body_head"]).encode("utf-8"))
                print("  POZN.: parsuje se jen prvních ~2 kB odpovědi, seznam může být neúplný.")
                for key, values in summary.items():
                    print("  %-15s %s" % (key + ":", ", ".join(values) if values else "-"))

    failed = [r for r in results if not r.ok]
    if args.fail_on == "none" or not failed:
        return 0
    if args.fail_on == "any":
        return 1
    blocking = [r for r in failed if r["priority"] == "P1" and r["verified"]]
    if blocking:
        if not args.json:
            print()
            print("SELHALY P1 zdroje: %s" % ", ".join(str(r["id"]) for r in blocking), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
