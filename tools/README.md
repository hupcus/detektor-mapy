# tools/ — desktop pipeline pro DetektorMapy

Skripty, které připraví **offline mapové vrstvy pro telefon**. Těžká práce
(stahování dlaždic, reprojekce z S-JTSK, georeference skenů, render LiDAR reliéfu)
se dělá tady na počítači — aplikace v terénu už jen čte hotové `.pmtiles`.

> **Runtime v aplikaci je vždy EPSG:3857 (Web Mercator).** Reprojekce z EPSG:5514
> (S-JTSK) se dělá výhradně tady, v GDALu, s `-r lanczos`. Nikdy za běhu v telefonu.

---

## 0. Instalace

```bash
# Python závislosti (Python 3.9+)
python3 -m pip install -r tools/requirements.txt

# Systémové nástroje (macOS / Homebrew)
brew install gdal      # gdalwarp, gdal_translate, gdal2tiles.py, gdalinfo, gdaltransform
brew install pdal      # jen pro dmr5g_hillshade.py (LAZ -> DTM)
```

GDAL je potřeba jen pro:
- `warp_scan.py` (vždy),
- `dmr5g_hillshade.py` (vždy, plus PDAL),
- `fetch_tiles.py` **jen** když služba neumí EPSG:3857 a stahuje se přes `--wms-crs EPSG:5514`.

Stahování WMTS dlaždic, stavba PMTiles i všechny testy fungují **bez GDALu**.

Ověření, že je vše na svém místě:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v   # 87 testů, bez sítě a bez GDALu
python3 tools/sources.py                                   # tabulka datových zdrojů
python3 tools/check_endpoints.py                           # jsou služby živé?
```

---

## 1. Přehled skriptů

| Skript | K čemu | Potřebuje GDAL |
|---|---|---|
| `sources.py` | registr datových zdrojů (jediné místo s URL, atribucemi, zoomy) | ne |
| `check_endpoints.py` | ověří dostupnost všech služeb, `--json` pro CI, exit 1 když spadne P1 zdroj | ne |
| `fetch_tiles.py` | bbox + zoomy + zdroj → adresář `{z}/{x}/{y}.png` | jen pro 5514 fallback |
| `build_pmtiles.py` | adresář dlaždic → `.pmtiles` (vlastní PMTiles v3 writer) + snippet do `layers.json` | ne |
| `warp_scan.py` | GCP z aplikace + sken → přesně warpnutý GeoTIFF → dlaždice → PMTiles | ano |
| `dmr5g_hillshade.py` | LAZ z ČÚZK → DTM → multi-directional hillshade + SVF → PMTiles | ano + PDAL |
| `uan_fetch.py` | ÚAN polygony (NPÚ ArcGIS) → GeoJSON, se stránkováním | ne |

Každý skript má `--help`. Skoro každý má `--dry-run`.

---

## 2. Kompletní postup pro nový region — příklad **okres Tábor**

Bounding box okresu Tábor (WGS84, zaokrouhleno): `14.45 49.20 15.10 49.60`
(pořadí je vždy **ZÁPAD JIH VÝCHOD SEVER**).

### 2.1 Ověř, že služby žijí

```bash
python3 tools/check_endpoints.py
# a když chceš zjistit skutečná jména vrstev a TileMatrixSetů:
python3 tools/check_endpoints.py --id ii_vm --capabilities
```

### 2.2 Kolik to bude dat?

```bash
python3 tools/fetch_tiles.py --source ii_vm \
    --bbox 14.45 49.20 15.10 49.60 --zoom 12-16 \
    --out data/tiles/ii_vm_tabor --dry-run
```

Vypíše počet dlaždic po zoomech a odhad velikosti. Pro celý okres na zoomu 17
už jde o gigabajty — historické mapy nemají smysl nad z=16, ortofoto nad z=18.

**Doporučené zoomy:** II./III. VM `12–16`, císařské otisky `13–17`,
ortofoto `13–17` (jen kolem lokalit), DMR 5G `13–17`.

### 2.3 Stáhni dlaždice

```bash
# II. vojenské mapování (WMTS, přímé dlaždice)
python3 tools/fetch_tiles.py --source ii_vm \
    --bbox 14.45 49.20 15.10 49.60 --zoom 12-16 \
    --out data/tiles/ii_vm_tabor --workers 4 --rate 8

# III. vojenské mapování
python3 tools/fetch_tiles.py --source iii_vm \
    --bbox 14.45 49.20 15.10 49.60 --zoom 12-16 \
    --out data/tiles/iii_vm_tabor

# DMR 5G stínovaný reliéf (WMS, dlaždice se renderují přes GetMap v 3857)
python3 tools/fetch_tiles.py --source dmr5g \
    --bbox 14.45 49.20 15.10 49.60 --zoom 13-16 \
    --out data/tiles/dmr5g_tabor

# Ortofoto (referenční podklad pro kalibraci)
python3 tools/fetch_tiles.py --source ortofoto \
    --bbox 14.60 49.35 14.80 49.50 --zoom 14-17 \
    --out data/tiles/ortofoto_tabor
```

Poznámky:
- Stahování je **resumovatelné** — když ho přerušíš, druhý běh doplní jen chybějící dlaždice.
- Jedna neúspěšná dlaždice běh neshodí; na konci se vypíše souhrn a prvních 50 chyb.
- `--rate` drží slušné tempo requestů. Neškubej s ním nahoru, jsou to cizí servery.
- Císařské otisky (`cisarske_jck`, `cisarske_msk`) jsou nativně v **EPSG:5514**,
  takže jejich dlaždicová cache **není** v XYZ mřížce. Použij ArcGIS export:
  ```bash
  python3 tools/fetch_tiles.py --source cisarske_jck --mode arcgis-export \
      --bbox 14.60 49.35 14.80 49.50 --zoom 14-17 --out data/tiles/cisarske_tabor
  ```
  Když by služba uměla jen WMS v 5514, jde to i přes GDAL (vyžaduje `brew install gdal`):
  ```bash
  python3 tools/fetch_tiles.py --source cisarske_jck --mode wms \
      --wms-crs EPSG:5514 --wms-version 1.1.1 \
      --bbox 14.60 49.35 14.80 49.50 --zoom 14-16 --out data/tiles/cisarske_tabor
  ```

### 2.4 Postav PMTiles

```bash
python3 tools/build_pmtiles.py \
    --tiles data/tiles/ii_vm_tabor \
    --out data/pmtiles/ii_vm_tabor.pmtiles \
    --name "II. vojenské mapování — Tábor" \
    --layer-id ii_vm_tabor --opacity 0.7 --verify
```

Skript:
- deduplikuje dlaždice podle SHA-256 (prázdné okraje se uloží jednou),
- vypíše, kolik se ušetřilo,
- s `--verify` archiv znovu otevře a porovná vzorek dlaždic bajt po bajtu,
- na stdout vypíše **hotový snippet do `layers.json`** — ten si zkopíruj.

Zopakuj pro každou vrstvu (`iii_vm_tabor`, `dmr5g_tabor`, `ortofoto_tabor`).

### 2.5 Nakopíruj do telefonu

Cílový adresář v telefonu:

```
Android/data/cz.hh.detektormapy/files/layers/
```

Přes USB (MTP), `adb push`, nebo Syncthing:

```bash
adb push data/pmtiles/ii_vm_tabor.pmtiles \
    /sdcard/Android/data/cz.hh.detektormapy/files/layers/
```

Vedle složky `layers/` leží `layers.json` — do jeho pole vrstev vlož snippety
vypsané z `build_pmtiles.py`:

```json
{
  "layers": [
    {
      "id": "ii_vm_tabor",
      "title": "II. vojenské mapování — Tábor",
      "type": "pmtiles-raster",
      "path": "layers/ii_vm_tabor.pmtiles",
      "format": "png",
      "minzoom": 12,
      "maxzoom": 16,
      "bounds": [14.45, 49.20, 15.10, 49.60],
      "opacity": 0.7,
      "visible": false,
      "attribution": "© CENIA / Rakouský státní archiv, Laboratoř geoinformatiky UJEP"
    }
  ]
}
```

Přidání nové mapy = nakopírovat `.pmtiles` + jeden řádek v JSONu. **Žádný nový release.**

---

## 3. Sken z Archivu ÚAZK (císařský otisk mimo pokryté kraje)

Skeny z `https://ags.cuzk.cz/archiv/` nejsou v žádném souřadnicovém systému.
Postup podle PLAN.md sekce 6, režim B:

1. V aplikaci otevři **GCP editor** (split-view starý sken vs. ortofoto),
   naklikej 6+ dvojic bodů (kostel, křižovatka, hráz rybníka) a exportuj GCP soubor.
2. Soubor (`scan_gcp.json`) přenes na počítač vedle skenu. Formát:

```json
{
  "image": "scan.jpg",
  "width": 5000,
  "height": 4000,
  "gcps": [
    {"px": 123.0, "py": 456.0, "lon": 14.6612, "lat": 49.4103}
  ],
  "created": "2026-08-19T21:00:00Z"
}
```

`px`/`py` = pixely ve skenu (počátek vlevo nahoře, `py` roste dolů),
`lon`/`lat` = WGS84.

3. Spusť pipeline:

```bash
# nejdřív nasucho, ať vidíš příkazy
python3 tools/warp_scan.py --gcp scan_gcp.json --out warped/otisk.tif --tps --dry-run

# a naostro, rovnou až do PMTiles
python3 tools/warp_scan.py \
    --gcp scan_gcp.json \
    --out warped/otisk.tif --tps \
    --tiles --zoom 13-17 \
    --pmtiles data/pmtiles/otisk_kat_uzemi.pmtiles \
    --name "Císařský otisk — k.ú. XY"
```

- `--tps` = thin plate spline (doporučeno od 6 bodů; skeny mají lokální deformace).
- `--polynomial --order 1` = afinní transformace, stačí 3 body, je „tužší".
- Bez přepínače si skript metodu vybere podle počtu bodů a řekne ti to.

---

## 4. Vlastní LiDAR reliéf (Fáze 5)

Výchozí hillshade z ČÚZK svítí z jediného azimutu 315° a tvary kolmé na světlo
v něm zanikají. `dmr5g_hillshade.py` kombinuje 6 azimutů a volitelně přimíchá
sky-view factor — úvozy, milíře a valy jsou pak výrazně ostřejší.

```bash
python3 tools/dmr5g_hillshade.py \
    --bbox 14.66 49.38 14.74 49.44 \
    --work data/dmr5g/lokalita \
    --svf --svf-weight 0.4 \
    --zoom 14-17 \
    --pmtiles data/pmtiles/dmr5g_lokalita.pmtiles
```

- LAZ soubory jsou velké — **dělej vždy jen malé lokality**, ne celý okres.
- Když se podle bboxu nic nenajde (feed ČÚZK nemusí u položek uvádět georss bbox),
  použij `--name-filter` s označením mapového listu.
- Máš-li už hotový DTM z QGISu, přeskoč stahování: `--dtm dtm.tif --no-download`.
- Ladění: `--azimuths`, `--altitude`, `--z-factor`, `--gamma`, `--svf-radius`.

---

## 5. Vrstva ÚAN (území s archeologickými nálezy)

> **Endpoint není ověřený.** PLAN.md u NPÚ služby výslovně říká „ověřit aktuální
> endpoint při implementaci". URL v `sources.py` je pracovní odhad.

```bash
# 1) dohledej skutečnou službu z portálové položky NPÚ
python3 tools/uan_fetch.py --discover

# 2) ověř sloupce nalezené vrstvy
python3 tools/uan_fetch.py --url "<URL>/FeatureServer/0" --fields

# 3) stáhni polygony pro zájmové území
export DETEKTORMAPY_UAN_URL="<URL>/FeatureServer/0"
python3 tools/uan_fetch.py --bbox 14.45 49.20 15.10 49.60 --out data/uan_tabor.geojson
```

Až bude endpoint potvrzený, přepiš ho v `tools/sources.py` a nastav `verified=True` —
tím se zařadí do exit kódu `check_endpoints.py`.

**Právní kontext:** ÚAN vrstva je tam kvůli tomu, aby bylo jasné, kde se
o archeologii jedná. Archeologický nález se do 2 dnů oznamuje muzeu/NPÚ a s místem
nálezu se 5 dní nemanipuluje (viz `docs/FIELD_GUIDE.md`).

---

## 6. ⚠️ Licenční upozornění (PLAN.md, sekce 3)

Prohlížecí služby **ČÚZK a CENIA jsou zdarma, ale nejsou určeny ke komerčnímu
užití a jejich redistribuce vyžaduje souhlas.**

- Aplikace je výhradně pro **osobní potřebu jediného uživatele**, dlaždice se
  cachují lokálně — to je v pořádku.
- **Nikdy nepublikuj APK ani `.pmtiles` s daty veřejně.** Soubory `.pmtiles` se
  **nekomitují do repozitáře** (velikost i licence) — přenášejí se ručně přes
  USB/Syncthing.
- Atribuce patří do about screenu aplikace:
  - © ČÚZK (ortofoto, DMR 5G, císařské otisky)
  - © CENIA (vojenská mapování)
  - © Rakouský státní archiv / Laboratoř geoinformatiky UJEP (I.–III. vojenské mapování)
  - © OpenStreetMap přispěvatelé (podkladová mapa)
  - © Národní památkový ústav (ÚAN)

---

## 7. Řešení problémů

| Příznak | Co s tím |
|---|---|
| `CHYBA: GDAL není v PATH` | `brew install gdal`; nebo se vyhni cestě přes 5514 (`--mode arcgis-export`) |
| `non-image odpověď (text/xml)` u každé dlaždice | špatné `LAYER` nebo `TILEMATRIXSET` — zjisti přes `check_endpoints.py --id <id> --capabilities` |
| Dlaždice jsou prázdné/bílé | bbox je mimo pokrytí vrstvy, nebo zoom mimo `min_zoom`–`max_zoom` zdroje |
| Stahování je pomalé | zvyš `--workers` na 6–8, ale `--rate` nech rozumný; jsou to cizí servery |
| PMTiles v aplikaci nejde otevřít | ověř archiv: `build_pmtiles.py … --verify`; zkontroluj, že cesta v `layers.json` sedí |
| `check_endpoints.py` vrací 1 | spadl ověřený P1 zdroj — endpointy CENIA/ČÚZK se historicky mění, zaktualizuj `sources.py` |
| Sken po warpu „teče" | příliš málo GCP bodů pro TPS — přidej body nebo přepni na `--polynomial --order 1` |
