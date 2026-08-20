# Datové zdroje

> Referenční přehled mapových služeb, ze kterých DetektorMapy staví offline vrstvy.
> Zadání: `PLAN.md` sekce 3, issue **F0-4**.
>
> **Jediný zdroj pravdy pro kód je `tools/sources.py`.** Tenhle dokument je jeho
> čitelná podoba pro člověka. Když se něco změní, mění se to **nejdřív v `sources.py`**
> a teprve pak tady. URL se nikde jinde nepíšou natvrdo.

---

## Licenční upozornění

Prohlížecí služby ČÚZK a CENIA jsou **zdarma, ale nejsou určené ke komerčnímu užití**
a jejich **redistribuce vyžaduje souhlas** poskytovatele. Tato aplikace je výhradně pro
osobní potřebu jednoho uživatele a dlaždice se cachují lokálně — to je v pořádku.

> **NIKDY nepublikovat APK ani PMTiles s daty veřejně.**

U vojenských mapování z geolab UJEP se uvádí citace
**© Rakouský státní archiv / Laboratoř geoinformatiky UJEP** — musí být vidět
v about screen aplikace (issue F5-3).

Atribuce všech použitých zdrojů patří do about screen: ČÚZK, CENIA, UJEP / Rakouský
státní archiv, OpenStreetMap, NPÚ.

---

## Jak číst tabulky

- **id** — identifikátor v `tools/sources.py`; používá se v CLI (`--id ii_vm`)
  i jako `layerId` v `layers.json`.
- **typ** — `wmts` / `wms` / `arcgis-wmts` / `arcgis-rest` / `atom`; určuje, jak
  `fetch_tiles.py` stahuje a jak `check_endpoints.py` ověřuje dostupnost.
- **CRS** — *nativní* projekce služby. Runtime aplikace je vždy EPSG:3857;
  reprojekce z EPSG:5514 dělá výhradně GDAL na desktopu s `-r lanczos`.
- **zoom** — použitelný rozsah nastavený v `sources.py`. Mimo něj služba buď nemá data,
  nebo vrací nesmyslně rozmazaný/nadinterpolovaný obraz.
- **stav ověřen** — datum poslední kontroly. Hodnoty níže vycházejí z auditu
  v PLAN.md sekce 3 (**srpen 2026**). Aktuální stav si vždy ověř sám:
  `python3 tools/check_endpoints.py`. Endpointy CENIA/ČÚZK se historicky mění často
  (PLAN.md sekce 10), proto běží měsíční kontrola v CI
  (`.github/workflows/endpoints.yml`).
- **P1 / P2** — priorita. Výpadek ověřeného **P1** zdroje shodí `check_endpoints.py`
  (exit 1) a založí issue.

---

## Přehledová tabulka

| id | název | typ | CRS | zoom | prio | stav ověřen |
|---|---|---|---|---|---|---|
| `ii_vm` | II. vojenské mapování 1836–52 (CENIA) | wmts | EPSG:3857 | 8–16 | P1 | 2026-08 (PLAN.md) |
| `iii_vm` | III. voj. mapování — speciálky 1:75 000 (CENIA) | wmts | EPSG:3857 | 8–16 | P2 | 2026-08 (PLAN.md) |
| `dmr5g` | DMR 5G — stínovaný reliéf (ČÚZK) | wms | EPSG:3857 | 10–17 | P1 | 2026-08 (PLAN.md) |
| `ortofoto` | Ortofoto ČR (ČÚZK) | arcgis-wmts | EPSG:3857 | 10–18 | P1 | 2026-08 (PLAN.md) |
| `cisarske_jck` | Císařské otisky — Jihočeský kraj | arcgis-wmts | EPSG:5514 | 10–18 | P2 | 2026-08 (PLAN.md) |
| `cisarske_msk` | Císařské otisky — Moravskoslezský kraj | arcgis-wmts | EPSG:5514 | 10–18 | P2 | 2026-08 (PLAN.md) |
| `ii_vm_ujep` | II. VM — záloha (oldmaps geolab, UJEP) | wms | EPSG:4326 | 8–15 | P2 | 2026-08-20 — **timeout** |
| `uan_npu` | ÚAN — území s archeologickými nálezy (NPÚ) | arcgis-rest | EPSG:5514 | 8–16 | P1 | ověřeno 2026-08-20 |
| `dmr5g_atom` | DMR 5G — surová LAZ data (ČÚZK ATOM) | atom | EPSG:5514 | 12–18 | P2 | 2026-08 (PLAN.md) |

---

## Detail zdrojů

### `ii_vm` — II. vojenské mapování 1836–52

| | |
|---|---|
| **Název** | II. vojenské mapování 1836–52 (CENIA) |
| **Typ** | WMTS (MapCache; umí RESTful i KVP GetTile) |
| **URL** | `https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wmts` |
| **Alternativa** | `https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wms` |
| **Vrstva** | `II_vojenske_mapovani`, TileMatrixSet `GoogleMapsCompatible` |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 8–16 |
| **Formát** | `image/png` |
| **Priorita** | **P1** |
| **Atribuce** | © CENIA / Rakouský státní archiv, Laboratoř geoinformatiky UJEP |

Celá ČR. **Pro detektoráře klíčová vrstva** — mapování je dost přesné na to, aby na něm
šlo hledat zaniklé usedlosti, mlýny a cesty, a zároveň dost staré, aby ukazovalo krajinu
před industrializací. Přesné jméno LAYER a TILEMATRIXSET ověř přes
`python3 tools/check_endpoints.py --id ii_vm --capabilities`.

### `iii_vm` — III. vojenské mapování, speciálky 1:75 000

| | |
|---|---|
| **Typ** | WMTS (MapCache) |
| **URL** | `https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wmts` |
| **Alternativa** | `https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wms` |
| **Vrstva** | `III_vojenske_mapovani`, TileMatrixSet `GoogleMapsCompatible` |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 8–16 |
| **Formát** | `image/png` |
| **Atribuce** | © CENIA / Rakouský státní archiv, Laboratoř geoinformatiky UJEP |

Celá ČR. Doplněk k II. VM — pozdější stav krajiny, dobré na křížové porovnání
(co v roce 1850 stálo a v roce 1880 už ne).

### `dmr5g` — DMR 5G, stínovaný reliéf

| | |
|---|---|
| **Typ** | WMS (ArcGIS ImageServer WMSServer) |
| **URL** | `https://ags.cuzk.gov.cz/arcgis2/services/dmr5g/ImageServer/WMSServer` |
| **Vrstva** | `dmr5g:GrayscaleHillshade`; styly `GrayscaleHillshade`, `SlopeRGBMap` |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 10–17 |
| **Formát** | `image/png` |
| **Priorita** | **P1** |
| **Atribuce** | © ČÚZK |

LiDARem odvozený reliéf — úvozy, milíře, valy, zaniklé cesty. Přesné jméno `LAYERS`
ověř přes GetCapabilities (`--capabilities`); u ImageServer WMS bývá vrstva `0` nebo
název stylu. Surová data viz `dmr5g_atom`.

### `ortofoto` — Ortofoto ČR

| | |
|---|---|
| **Typ** | ArcGIS WMTS |
| **URL** | `https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer/WMTS` |
| **Capabilities** | `.../MapServer/WMTS/1.0.0/WMTSCapabilities.xml` |
| **Vrstva** | `ORTOFOTO`, TileMatrixSet `default028mm` |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 10–18 |
| **Formát** | `image/jpeg` |
| **Priorita** | **P1** |
| **Atribuce** | © ČÚZK |

Referenční podklad pro kalibraci (GCP editor, Fáze 3) — proti ortofotu se páruje stará
mapa. ArcGIS MapServer nabízí i XYZ cache `.../MapServer/tile/{z}/{y}/{x}`, ta je ale
v tilingu služby, ne nutně v GoogleMapsCompatible.

### `cisarske_jck` — Císařské otisky, Jihočeský kraj

| | |
|---|---|
| **Typ** | ArcGIS WMTS |
| **URL** | `https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/cisarske_otisky/MapServer/WMTS` |
| **REST** | `https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/cisarske_otisky/MapServer` |
| **Nativní CRS** | **EPSG:5514** |
| **Zoom** | 10–18 |
| **Atribuce** | © Jihočeský kraj / ČÚZK |

Bezešvá mozaika císařských otisků stabilního katastru. **Pozor:** nativně S-JTSK, takže
TileMatrixSet **není** GoogleMapsCompatible a dlaždice nelze brát 1:1 jako XYZ. Pro
PMTiles použij cestu přes WMS/export s reprojekcí (`fetch_tiles.py --via-wms`) nebo si
šablonu ověř přes `--capabilities`.

### `cisarske_msk` — Císařské otisky, Moravskoslezský kraj

| | |
|---|---|
| **Typ** | ArcGIS WMTS |
| **URL** | `https://gis2.msk.cz/arcgis/rest/services/podklad/podklad_cis_otisky/MapServer/WMTS` |
| **Nativní CRS** | **EPSG:5514** |
| **Zoom** | 10–18 |
| **Atribuce** | © Moravskoslezský kraj / ČÚZK |

Totéž co u `cisarske_jck` včetně poznámky k EPSG:5514.

### `ii_vm_ujep` — II. VM, záloha z oldmaps geolab

| | |
|---|---|
| **Typ** | WMS |
| **URL** | `http://mapserver.ujep.cz/ArcGIS/services/Geolab/IIVM_WMS/MapServer/WMSServer` |
| **Vrstva** | `0` (ověř přes GetCapabilities) |
| **Nativní CRS** | EPSG:4326 |
| **Zoom** | 8–15 |
| **Atribuce** | © Rakouský státní archiv / Laboratoř geoinformatiky UJEP |

Záloha pro případ výpadku CENIA. **Pouze HTTP, bez TLS.**

> Kontrola 2026-08-20: server neodpověděl do 15 s (timeout). Jde o P2 zálohu, takže
> to nic neblokuje — primární `ii_vm` z CENIA běží. Kdyby CENIA vypadla natrvalo,
> je nutné ověřit, zda mapserver UJEP ještě žije, než se na něj spolehneš.

### `uan_npu` — ÚAN, území s archeologickými nálezy

| | |
|---|---|
| **Typ** | ArcGIS REST (MapServer, `capabilities: Map,Query,Data`) |
| **URL** | `https://geoportal.npu.cz/arcgis/rest/services/Tematicke/CP_UAN/MapServer` |
| **Portal item** | `4e5f269e38004377bdc5fa8a6cbec58d` |
| **Metadata** | `https://www.arcgis.com/sharing/rest/content/items/4e5f269e38004377bdc5fa8a6cbec58d?f=json` |
| **Nativní CRS** | EPSG:5514 (wkid 102067) — dotaz se posílá s `outSR=4326`, takže výstup je rovnou WGS84 |
| **Zoom** | 8–16 |
| **Priorita** | **P1**, `verified=True` |
| **Atribuce** | © Národní památkový ústav |
| **Stav ověřen** | 2026-08-20 |

Vrstvy služby (index = argument `--layer` u `tools/uan_fetch.py`):

| Index | Vrstva | Význam pro hledání |
|---|---|---|
| `0` | kategorie I (prokázaná území) | prokázaný archeologický terén — nejpřísnější režim |
| `1` | kategorie II (předpokládaná území) | důvodný předpoklad výskytu nálezů |
| `2` | kategorie II (pásmo) | ochranné pásmo kolem kategorie II |
| `3` | kategorie IV (vytěžená území) | území bez archeologického potenciálu |

Ověření proběhlo přes portal item, který ukazuje na `Map Service` s výše uvedenou URL.
Původní odhad `gis.up.npu.cz/.../ISAD/uan/FeatureServer/0` z první iterace **neexistuje**
(DNS selže) — kdyby se endpoint zase změnil, postup je stejný: přečíst metadata itemu,
nastavit `DETEKTORMAPY_UAN_URL`, nebo přepsat URL v `tools/sources.py`.

Stažení polygonů pro zájmové území:

```bash
python3 tools/uan_fetch.py --bbox 15.20 49.60 15.30 49.66 --layer 1 --out uan_ii.geojson
```

Vrácené atributy: `Nazev`, `ID_SAS`, `Kategorie`, `ID_polygonu`, `Stav_polygonu`.

Vrstva je právně důležitá: ÚAN I/II jsou území, kde je detektorové hledání bez
povolení zakázané. Viz `docs/FIELD_GUIDE.md`, sekce Právo.

### `dmr5g_atom` — surová LAZ data DMR 5G

| | |
|---|---|
| **Typ** | ATOM feed (open data) |
| **URL** | `https://atom.cuzk.gov.cz/DMR5G-SJTSK/DMR5G-SJTSK.xml` |
| **Nativní CRS** | EPSG:5514 |
| **Formát** | LAZ |
| **Atribuce** | © ČÚZK |

Vstup pro `tools/dmr5g_hillshade.py` (Fáze 5, F5-1): LAZ → DTM → multi-directional
hillshade + sky-view factor → PMTiles. Cílem je výrazně čitelnější reliéf než výchozí
ČÚZK hillshade.

---

## Negeoreferencované skeny — ruční pipeline

Tyhle zdroje **nejsou** v `tools/sources.py`, protože se nestahují automaticky.

### Císařské otisky stabilního katastru mimo kraje výše

Čechy 1826–43, Morava 1824–36. Skeny jednotlivých listů z aplikace **Archiv ÚAZK**:
`https://ags.cuzk.cz/archiv/`.

Skeny **nejsou transformované do žádného souřadnicového systému**. Postup:
georeference v QGIS (GCP + TPS) → `gdalwarp -tps` (obálka `tools/warp_scan.py`) → PMTiles.
GCP body lze připravit i v aplikaci v Režimu B (F3-4).

### I. vojenské mapování (1764–68)

`oldmaps.geolab.cz`. Mapováno „od oka" — **globální transformace nedává smysl**.
Použití: lokální warp jen pro konkrétní zájmové území, jinak jen ruční overlay
v aplikaci (Režim A / `ImageSource` se 4 rohy, F3-5).

---

## Podkladová mapa

OpenStreetMap raster/vector dlaždice — OpenFreeMap, nebo vlastní PMTiles export
(PLAN.md sekce 2, issue F1-5). Licenčně čisté. Atribuce **© OpenStreetMap contributors**
patří do about screen.

`TODO ověřit`: konkrétní zdroj vector PMTiles pro ČR (Protomaps build vs. OpenFreeMap
extract) PLAN.md neurčuje.

---

## Projekce: co jde online a co ne (ověřeno 2026-08-20)

Aplikace renderuje výhradně ve Web Mercatoru (EPSG:3857). České služby jsou ale
publikované v S-JTSK, a **runtime reprojekce je zakázaná** (PLAN.md sekce 2). Z toho
plyne rozdělení zdrojů na dvě skupiny:

| Zdroj | Nativní CRS | Online v appce? | Proč |
|---|---|---|---|
| CENIA II. a III. vojenské mapování | EPSG:5514 | **ne** | WMTS nabízí jediný TileMatrixSet `jtsk:epsg:5514`, WMS ohlašuje pouze `SRS=EPSG:5514`. Reprojekci nedělá. → offline PMTiles přes `tools/` |
| ČÚZK Ortofoto | EPSG:5514 | **ano** | WMTS je sice v 5514, ale ArcGIS REST `MapServer/export` s `bboxSR=3857&imageSR=3857` přepočítá dlaždici na serveru |
| Císařské otisky (JčK, MSK) | EPSG:5514 | **ano** | stejný trik s `export` |
| ČÚZK DMR 5G | — | **ano** | ArcGIS `WMSServer` přijímá `CRS=EPSG:3857` přímo (ověřeno GetMap dotazem) |

Proto má `LayerKind` hodnotu `arcgis`: je to jediná online cesta, jak dostat české
5514 služby do Web Mercatoru, a reprojekci dělá server, ne aplikace.

Konkrétní ověření DMR 5G: `LAYERS=dmr5g:GrayscaleHillshade` je **název vrstvy**, ne
kombinace vrstvy a stylu — služba vystavuje `dmr5g`, `dmr5g:GrayscaleHillshade`,
`dmr5g:AspectRGBMap` atd. jako samostatné vrstvy, každou jen se stylem `default`.
Zápis `LAYERS=dmr5g&STYLES=GrayscaleHillshade` vrátí **HTTP 200 s XML chybou**, ne obrázek.

## Jak přidat nový zdroj

1. **Přidej `Source(...)` do `tools/sources.py`** — do seznamu `SOURCES`.
   Povinné: `id`, `title`, `type`, `url`. Dál doplň `layer`, `fmt`/`ext`,
   `attribution`, `crs`, `min_zoom`/`max_zoom`, `priority`.
   - Pokud endpoint nemáš potvrzený, nastav `verified=False` a přidej `env_override`,
     ať jde URL opravit bez zásahu do kódu.
   - `capabilities_url` nastav jen tam, kde odvozená URL nefunguje
     (typicky ArcGIS `.../WMTSCapabilities.xml`).
2. **Ověř, že služba odpovídá:**
   ```bash
   python3 tools/check_endpoints.py --id <nove_id> --capabilities
   ```
   Výpis ukáže skutečná jména vrstev, TileMatrixSetů a formátů — podle nich uprav
   `layer` a `tilematrixset`.
3. **Stáhni dlaždice a postav PMTiles** podle `tools/README.md`
   (`fetch_tiles.py` → `build_pmtiles.py`) pro svůj bbox a rozsah zoomů.
4. **Zkopíruj `.pmtiles`** do telefonu do
   `Android/data/cz.hh.detektormapy/files/layers/`.
5. **Přidej řádek do `layers.json`** vedle souboru — `id`, `title`, `kind: "pmtiles"`,
   `source` (jméno souboru), `attribution`, `defaultOpacity`, `minZoom`/`maxZoom`,
   `order`. Nový release aplikace není potřeba.
6. **Doplň zdroj do tohoto dokumentu** (přehledová tabulka + detail) a **atribuci do
   about screen**. Bez atribuce se zdroj nepoužívá.
7. Zkontroluj licenci. Když není jasné, že smíš data držet pro osobní potřebu,
   zdroj nepřidávej.
