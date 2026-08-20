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
státní archiv, OpenStreetMap, NPÚ, Jihočeský / Moravskoslezský / Karlovarský kraj,
VÚGTK (Chartae-antiquae.cz).

Podmínky pro **veřejné šíření** aplikace se řeší centrálně v issue **#34** (F6-6).
Stav k 2026-08-20: Jihočeský kraj u WMTS otisků výslovně uvádí, že opětovné užití
pro obchodní účely je zamezeno (ochranné znaky ČÚZK); Chartae Antiquae (VÚGTK) ani
Karlovarský kraj podmínky u služby nepublikují — před veřejným vydáním je nutné se
zeptat. Detaily v issue.

---

## Jak číst tabulky

- **id** — identifikátor v `tools/sources.py`; používá se v CLI (`--id ii_vm`)
  i jako `layerId` v `layers.json`.
- **typ** — `wmts` / `wms` / `arcgis-wmts` / `arcgis-rest` / `atom` / `xyz`; určuje, jak
  `fetch_tiles.py` stahuje a jak `check_endpoints.py` ověřuje dostupnost.
  `xyz` je prostá `{z}/{x}/{y}` šablona bez Capabilities — dostupnost se ověřuje
  stažením konkrétní dlaždice a empty-tile heuristikou (obrázek > 400 B).
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
| `cisarske_kvk` | Císařské otisky — Karlovarský kraj | arcgis-rest | EPSG:5514 | 10–18 | P2 | **ověřeno 2026-08-20** |
| `muller_cechy` | Müllerova mapa Čech 1720 (Chartae Antiquae) | xyz | EPSG:3857 | 5–14 | P2 | **ověřeno 2026-08-20** |
| `muller_morava` | Müllerova mapa Moravy 1716 (Chartae Antiquae) | xyz | EPSG:3857 | 5–14 | P2 | **ověřeno 2026-08-20** |
| `vm1_chartae` | I. vojenské mapování 1764–68 (Chartae Antiquae) | xyz | EPSG:3857 | 5–15 | P2 | **ověřeno 2026-08-20** |
| `vm2_chartae` | II. vojenské mapování — online (Chartae Antiquae) | xyz | EPSG:3857 | 5–16 | P2 | **ověřeno 2026-08-20** |
| `vm3_topo_chartae` | III. VM 1:25 000 — topografické sekce (Chartae) | xyz | EPSG:3857 | 5–16 | P2 | **ověřeno 2026-08-20** |
| `ortofoto_wm` | Ortofoto ČR — nativní WM cache (ČÚZK) | xyz | EPSG:3857 | 7–20 | P1 | **ověřeno 2026-08-20** |
| `ztm_wm` | Základní topografická mapa — WM cache (ČÚZK) | xyz | EPSG:3857 | 4–19 | P2 | **ověřeno 2026-08-20** |
| `ii_vm_ujep` | II. VM — záloha (oldmaps geolab, UJEP) | wms | EPSG:4326 | 8–15 | P2 | 2026-08-20 — **mrtvý** (3. timeout) |
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

### `cisarske_kvk` — Císařské otisky, Karlovarský kraj

| | |
|---|---|
| **Typ** | ArcGIS REST (dynamický MapServer, bez tile cache) |
| **URL** | `https://geo-ags.kr-karlovarsky.cz/arcgis/rest/services/Image/CisarskeOtisky/MapServer` |
| **Nativní CRS** | **EPSG:5514** (wkid 102067) |
| **Zoom** | 10–18 |
| **Atribuce** | © Karlovarský kraj / ČÚZK |
| **Stav ověřen** | 2026-08-20 |

Bezešvá mozaika jako u JčK a MSK, jen bez WMTS cache — služba je dynamická.
`export` s `bboxSR=3857&imageSR=3857` vrací skutečný obraz (47 kB PNG u Karlových
Varů), takže v aplikaci jede online přes `LayerKind.ARCGIS`. Endpoint byl objeven
přes krajskou Experience aplikaci (AGOL item `3f9f806284b64728abb8690f74b2425e`);
na `gis.kr-karlovarsky.cz` (bez `geo-ags`) spojení jen resetuje.

### `muller_cechy` / `muller_morava` — Müllerovy mapy (Chartae Antiquae)

| | |
|---|---|
| **Typ** | XYZ dlaždice (typ `xyz` v sources.py) |
| **URL** | `https://www.chartae-antiquae.cz/TMS/MullerC/{z}/{x}/{y}` (Čechy 1720) |
| | `https://www.chartae-antiquae.cz/TMS/MullerM/{z}/{x}/{y}` (Morava 1716) |
| **Nativní CRS** | EPSG:3857 (Web Mercator) |
| **Zoom** | 5–14 (z15 vrací prázdnou PNG) |
| **Atribuce** | © VÚGTK / Chartae-antiquae.cz (Virtuální mapová sbírka) |
| **Stav ověřen** | 2026-08-20 |

**Pozor na název:** cesta obsahuje `TMS`, ale osa Y se **nepřevrací** — je to
standardní XYZ (ověřeno: správná dlaždice vrací 18 kB JPEG, y-flip vrací prázdnou
334B PNG). Dlaždice mimo pokrytí jsou průhledné PNG ~334 B — odtud hranice 400 B
v empty-tile heuristice. Georeference je přibližná: mapa vznikla před triangulací,
čekat odchylky v řádu stovek metrů až kilometrů. Tohle je pravděpodobně i zdroj,
který zobrazuje aplikace KATNA.

> **Pozor při lokálním `check_endpoints.py`:** server vyžaduje TLS 1.3. Systémový
> Python 3.9 na macOS má LibreSSL 2.8.3 bez TLS 1.3, takže probe hlásí „TLS chyba"
> — to **není** výpadek služby (curl i aplikace TLS 1.3 umí, dlaždice ověřeny
> dekódováním 256×256 JPEG). V CI s OpenSSL kontrola projde.
>
> Server je **TLS 1.3 only** (handshake s `--tls-max 1.2` selže). Android umí
> TLS 1.3 od API 29, takže na Androidu 8/9 (minSdk 26–28) by chartae vrstvy tiše
> nenaběhly — na cílovém zařízení (API 36) to problém není.

### `vm1_chartae` — I. vojenské mapování (Chartae Antiquae)

| | |
|---|---|
| **Typ** | XYZ dlaždice |
| **URL** | `https://www.chartae-antiquae.cz/TMS/Military1/{z}/{x}/{y}` |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 5–15 |
| **Atribuce** | © VÚGTK / Chartae-antiquae.cz |
| **Stav ověřen** | 2026-08-20 (dlaždice u Úpice, 26 kB JPEG) |

Globálně zwarpovaná verze josefského mapování. **Mapováno od oka bez trigonometrické
sítě, takže georeference je jen orientační** — mapa sama v sobě není konzistentní
a lokální odchylky mohou být velké. V katalogu aplikace má vrstva
`manualAlignment = true`: panel vrstev u ní zobrazuje varování a pro přesnou práci
se používá ruční overlay (Vrstvy → „Přiložit sken…", Režim A ukládá kalibraci per
oblast). Skeny listů pro ruční přiložení: `oldmaps.fzp.ujep.cz` (viz níže).

### `vm2_chartae` / `vm3_topo_chartae` — II. a III. VM online (Chartae Antiquae)

| | |
|---|---|
| **Typ** | XYZ dlaždice |
| **URL** | `https://www.chartae-antiquae.cz/TMS/Military2/{z}/{x}/{y}` (II. VM 1:28 800) |
| | `https://www.chartae-antiquae.cz/TMS/Military3/{z}/{x}/{y}` (III. VM 1:25 000) |
| **Nativní CRS** | EPSG:3857 |
| **Zoom** | 5–16 |
| **Atribuce** | © VÚGTK / Chartae-antiquae.cz |
| **Stav ověřen** | 2026-08-20 (dlaždice u Úpice včetně z16) |

Online doplněk k CENIA zdrojům (ty jsou kvůli S-JTSK jen offline): `Military2`
funguje hned bez pipeline a nahrazuje mrtvou zálohu `ii_vm_ujep`; `Military3`
jsou **topografické sekce 1:25 000** — podrobnější než speciálky 1:75 000,
které jako jediné má CENIA. Chartae nabízí i `Military3_75` (speciálky, data
jen do z15) a `Military2_144`; názvy vrstev ověřeny z konfigurace jejich
porovnávače map.

### `ortofoto_wm` / `ztm_wm` — nativní Web Mercator cache ČÚZK

| | |
|---|---|
| **Typ** | XYZ (ArcGIS tile cache — pozor, `/tile/{z}/{y}/{x}`, řádek před sloupcem) |
| **URL** | `https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}` |
| | `https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}` |
| **Nativní CRS** | EPSG:3857 (wkid 102100), LOD 0–23 |
| **Zoom** | ortofoto 7–20, ZTM 4–19 (ověřeno dlaždicemi) |
| **Atribuce** | © ČÚZK |
| **Stav ověřen** | 2026-08-20 |

Předgenerované dlaždice přímo ve Web Mercatoru — žádný serverový přepočet
per request. V aplikaci: vrstva `ortofoto` od katalogu v2 jede z `ORTOFOTO_WM`
(rychlejší než dřívější `export`), `ztm` je nový druhý podklad vedle OSM.
Stará S-JTSK služba `ortofoto` zůstává v registru pro WMTS stahování a jako
záloha.

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
>
> Třetí kontrola týž den: opět timeout — **považuj server za mrtvý**. Web oldmaps
> se přestěhoval na `oldmaps.fzp.ujep.cz` (skenový prohlížeč funguje, sekce
> `map_root=1vm/2vm/3vm/mul`), WMS náhradu UJEP nevystavuje. Jako online záloha
> II. VM může nouzově posloužit `https://www.chartae-antiquae.cz/TMS/Military2/{z}/{x}/{y}`.

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

## Kraje a císařské otisky — přehled (ověřeno 2026-08-20)

Vlastní georeferencovanou službu císařských otisků provozují **3 kraje ze 14**.
Zbytek byl prověřen výčtem ArcGIS REST adresářů a hledáním na krajských geoportálech:

| Kraj | Vlastní služba | Poznámka |
|---|---|---|
| Jihočeský | **ano** (`cisarske_jck`) | nový geoportál `geoportal.kraj-jihocesky.gov.cz`, endpoint beze změny |
| Moravskoslezský | **ano** (`cisarske_msk`) | |
| Karlovarský | **ano** (`cisarske_kvk`) | objeveno v tomto auditu |
| **Královéhradecký** | **ne** | `mapy.kr-kralovehradecky.cz` přesměrovává na mrtvou stránku `khk.cz/gis.htm`; `mapy.khk.cz` je T-MapServer jen se současnými tématy. **Pro Úpici → ruční pipeline z ÚAZK skenů** (viz níže). |
| Plzeňský | ne | ArcGIS 10.03 na `mapy.plzensky-kraj.cz` vyčten celý — otisky tam nejsou |
| Zlínský | ne | ArcGIS 10.91 na `mapy.kr-zlinsky.cz` vyčten — nejsou |
| Jihomoravský | ne | `gis.jmk.cz` vyčten — nejsou (má ale `Ortofoto_1953`, cache v 5514) |
| Praha | ne | `gs-pub.praha.eu` nemá; starý prohlížeč ÚRM `up.webmap.cz/cisar` sám hlásí „aplikace není funkční" |
| Středočeský, Ústecký, Liberecký, Pardubický, Vysočina, Olomoucký | nenalezeno | žádný veřejný ArcGIS adresář s otisky; existují jen **městské** aplikace (Ústí n. L., Liberec) |

### Negeoreferencované skeny — ruční pipeline

Tyhle zdroje **nejsou** v `tools/sources.py`, protože se nestahují automaticky.

### Císařské otisky stabilního katastru mimo kraje výše

Čechy 1826–43, Morava 1824–36. Skeny jednotlivých listů z aplikace **Archiv ÚAZK**:
`https://ags.cuzk.gov.cz/archiv/` (stará doména `ags.cuzk.cz` přesměrovává).

**Stahování je zautomatizované — `tools/archiv_fetch.py`** (ověřeno 2026-08-20 na
katastru Úpice, 5 skenů):

```bash
python3 tools/archiv_fetch.py --katastr Úpice --out skeny/          # vejde se do 1 requestu
python3 tools/archiv_fetch.py --katastr Úpice --out skeny/ --full   # plné rozlišení (GDAL)
python3 tools/archiv_fetch.py --lonlat 16.0116 50.5123 --list-only  # jen vypsat listy
```

Jak to uvnitř funguje (reverse z veřejné aplikace Archiv):
- anonymní token vydává GP job `arcgis2/.../GenerateToken` (referer `ags.cuzk.gov.cz/archiv/`),
- klady listů: `arcgis4/rest/services/Archiv/klady/MapServer` vrstva 3 (atributy
  `cio_SIGN_INV`, `om_SIGN_INV`, `kme_SIGN_NOMEN` = prefixy jmen skenů; `--serie cio|om|kme`),
- skeny: ImageServer `Archiv/archiv_nespojene_stable` — SR sice hlásí 5514, ale
  souřadnice jsou **pixelový rám** (extent 0…9394×8529) → skeny georeferencované
  NEJSOU; export přes `lockRaster` mozaiku, limit 15000×4100 px na request
  (`--full` proto stahuje pruhy a lepí je GDALem).

Postup po stažení: georeference v QGIS (GCP + TPS) → `gdalwarp -tps` (obálka
`tools/warp_scan.py`) → PMTiles. GCP body lze připravit i v aplikaci v Režimu B
(F3-4), rychlá varianta je Vrstvy → „Přiložit sken…".

**Žádná celostátní georeferencovaná mozaika otisků u ČÚZK neexistuje** — backend
prohlížeče kreslí jen podkladové služby a skeny v pixelovém rámu.

### Indikační skici stabilního katastru

**Jen skeny, žádná služba.** Prověřeno 2026-08-20:

- **Čechy** (Národní archiv, fond SK): skeny v prohlížeči Archivu ÚAZK výše —
  stejný skenový backend, žádná georeference.
- **Morava** (Moravský zemský archiv): starý web `mza.cz/indikacniskici` už
  odkazuje do prohlížeče ÚAZK (`ags.cuzk.cz/archiv/?xy=…`) a nabízí objednávku
  reprodukcí; vlastní dlaždicovou službu nemá.
- Žádný krajský geoportál indikační skici jako službu nevystavuje.

Použití v aplikaci: stáhnout sken → Vrstvy → „Přiložit sken…" (4 rohy), případně
plná pipeline přes QGIS + `warp_scan.py` jako u otisků.

### I. vojenské mapování (1764–68)

Skeny: `oldmaps.fzp.ujep.cz` (dřív `oldmaps.geolab.cz` — doména stále žije a
přesměrovává; sekce `map_root=1vm`, regiony ce/mo/sl). Mapováno „od oka" —
**globální transformace nedává smysl**. Použití: lokální warp jen pro konkrétní
zájmové území, jinak ruční overlay v aplikaci (Režim A / `ImageSource` se 4 rohy,
F3-5).

Online navíc existuje globálně zwarpovaná verze `vm1_chartae` (viz výše) — hodí se
na orientaci „kde zhruba stála ves", ne na přesné zaměření. V aplikaci je proto
označená `manualAlignment` a panel vrstev to říká nahlas.

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
| Císařské otisky (JčK, MSK, KVK) | EPSG:5514 | **ano** | stejný trik s `export` (KVK ověřeno 2026-08-20) |
| ČÚZK DMR 5G | — | **ano** | ArcGIS `WMSServer` přijímá `CRS=EPSG:3857` přímo (ověřeno GetMap dotazem) |
| Chartae Antiquae (Müller, Military1…) | EPSG:3857 | **ano** | nativní XYZ dlaždice ve Web Mercatoru, žádná reprojekce potřeba |

Bonus z auditu 2026-08-20: ČÚZK mezitím vystavil **nativní Web Mercator cache**
`ORTOFOTO_WM` a `ZTM_WM` — detaily viz jejich sekce výše. Od katalogu v2 je
vrstva `ortofoto` přepnutá na tuhle cache a `ztm` je nový druhý podklad.

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
