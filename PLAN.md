# DetektorMapy — komplexní plán vývoje a nasazení

> **Instrukce pro Claude Code:** Tento dokument je zadání projektu. Tvým úkolem je:
> 1. Založit nový privátní GitHub repozitář `detektor-mapy` (přes `gh repo create detektor-mapy --private`).
> 2. Vytvořit labels, milestones a GitHub issues přesně podle sekce 9 (každý task = jedna issue, s popisem, acceptance criteria a labelem fáze).
> 3. Inicializovat strukturu projektu podle sekce 4 a začít implementovat Fázi 0 a 1.
> 4. Po každé dokončené fázi vytvořit tag `v0.x` a aktualizovat `CHANGELOG.md`.
> Pracuj iterativně, jedna issue = jeden branch = jeden PR (i když jsi jediný vývojář, kvůli historii).

---

## 1. Vize a scope

Soukromá Android aplikace (jediný uživatel, žádná publikace na Google Play) pro hledání s detektorem kovů v ČR:

- **Historické mapy ČR jako překryvné vrstvy nad OpenStreetMap** — offline, s plynulým přepínáním a nastavitelnou průhledností.
- **Ruční doladění georeference** posunutých map přímo v terénu (drag/rotate/scale overlay) + import přesně warpnutých rastrů připravených na desktopu.
- **Značení míst** (waypointy, plánované lokality, prohledané zóny) a **evidence nálezů s fotkami** a GPS.
- **LiDAR reliéf (DMR 5G)** jako vrstva — úvozy, milíře, valy, zaniklé cesty.
- **Vrstva ÚAN** (území s archeologickými nálezy, NPÚ) pro právní kontext.
- Vše funguje **plně offline** v terénu; online jen při přípravě (stahování dlaždic).

**Non-goals (explicitně mimo scope):** komunita, účty, sync na server, iOS, publikace, monetizace, live sdílení polohy.

**Inspirace funkcí:** Rodek (Metal Detecting Hub) — přebíráme koncept overlay historických map, finds log s fotkami, grid prohledaných území. Nepřebíráme komunitu a marketplace.

---

## 2. Technologická rozhodnutí (závazná)

| Oblast | Volba | Zdůvodnění |
|---|---|---|
| Jazyk | Kotlin, min SDK 26, target SDK aktuální | standard |
| UI | Jetpack Compose + Material 3 | rychlý vývoj, jediný vývojář |
| Mapa | **MapLibre GL Native (org.maplibre.gl:android-sdk)** | open-source, raster i vector sources, custom layers, offline |
| Podkladová mapa | OpenStreetMap raster/vector dlaždice (OpenFreeMap nebo vlastní PMTiles export) | licenčně čisté |
| Offline formát | **PMTiles** (raster overlay i basemap), fallback MBTiles | jeden soubor na vrstvu, range-requesty, čte se přímo z lokálního úložiště |
| DB | Room (SQLite) | nálezy, waypointy, kalibrace vrstev |
| Foto | CameraX + uložení do app-specific storage, EXIF GPS | |
| DI | Hilt | |
| Preprocessing (desktop) | Python + GDAL (`gdalwarp`, `gdal2tiles`/`rio-pmtiles`), skripty v repu pod `/tools` | těžká georeference se dělá na PC, ne v telefonu |
| CI | GitHub Actions: build + unit testy + release APK artifact | |
| Distribuce | Podepsaný APK z GitHub Releases, instalace sideload; volitelně Obtainium pro auto-update | žádný Play Store |

**Souřadnicové systémy:** interní zobrazení vždy Web Mercator (EPSG:3857). České služby jsou v S-JTSK (EPSG:5514) — konverze se řeší v preprocessing pipeline (GDAL), nikdy za běhu v aplikaci. Výjimka: WMS služby, které umí EPSG:3857 nativně, lze proxovat přímo.

---

## 3. Datové zdroje (ověřené, srpen 2026)

### Hotové georeferencované služby → stáhnout do PMTiles

| Vrstva | Zdroj | Poznámka |
|---|---|---|
| II. vojenské mapování 1836–52 | `https://gis.cenia.cz/mapcache/II_vojenske_mapovani/wmts` (i `/wms`) | celá ČR, pro detektoráře klíčová |
| III. voj. mapování — speciálky 1:75 000 | `https://gis.cenia.cz/mapcache/III_vojenske_mapovani/wmts` | celá ČR |
| DMR 5G stínovaný reliéf | `https://ags.cuzk.gov.cz/arcgis2/services/dmr5g/ImageServer/WMSServer` (styly GrayscaleHillshade, SlopeRGBMap…) | LiDAR; surová data LAZ jsou open data přes ATOM `https://atom.cuzk.gov.cz/DMR5G-SJTSK/DMR5G-SJTSK.xml` → možnost vlastního renderu (multi-directional hillshade) ve Fázi 5 |
| Ortofoto ČR | `https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer/WMTS` | referenční podklad pro kalibraci |
| Císařské otisky — Jihočeský kraj | `https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/cisarske_otisky/MapServer/WMTS` | bezešvá, EPSG:5514 |
| Císařské otisky — Moravskoslezský kraj | `https://gis2.msk.cz/arcgis/rest/services/podklad/podklad_cis_otisky/MapServer/WMTS` | |
| II. VM (alternativa, oldmaps geolab) | `http://mapserver.ujep.cz/ArcGIS/services/Geolab/IIVM_WMS/MapServer/WMSServer` | záloha |
| ÚAN — území s archeologickými nálezy | ArcGIS služby NPÚ (isad.npu.cz / npu.maps.arcgis.com, item `4e5f269e38004377bdc5fa8a6cbec58d`) | ověřit aktuální endpoint při implementaci |

### Negeoreferencované skeny → ruční pipeline

- **Císařské otisky stabilního katastru** (Čechy 1826–43, Morava 1824–36) mimo kraje výše: skeny jednotlivých listů z aplikace Archiv ÚAZK (`https://ags.cuzk.cz/archiv/`). Skeny nejsou transformované do žádného systému → georeference v QGIS (GCP + TPS) → `gdalwarp -tps` → PMTiles.
- **I. vojenské mapování** (1764–68, oldmaps.geolab.cz): mapováno „od oka", globální transformace nedává smysl. Použití: lokální warp jen pro konkrétní zájmové území, jinak jen ruční overlay v aplikaci.

### Licenční poznámka
Prohlížecí služby ČÚZK/CENIA jsou zdarma, ale nejsou určeny ke komerčnímu užití a redistribuce vyžaduje souhlas. Aplikace je výhradně pro osobní potřebu jednoho uživatele, dlaždice se cachují lokálně — v pořádku. **Nikdy nepublikovat APK s daty veřejně.** Citace u vojenských mapování geolab: © Austrian State Archive / Laboratoř geoinformatiky UJEP — uvést v about screen.

---

## 4. Architektura a struktura repozitáře

```
detektor-mapy/
├── app/                          # Android aplikace
│   └── src/main/java/cz/hh/detektormapy/
│       ├── ui/                   # Compose screens
│       │   ├── map/              # MapScreen, LayerPanel, CalibrationOverlay
│       │   ├── finds/            # FindsList, FindDetail, FindCapture
│       │   ├── places/           # Waypointy, plánované lokality
│       │   └── settings/
│       ├── map/                  # MapLibre wrapper, LayerManager, PMTiles source
│       ├── calibration/          # affine/similarity transform, GCP editor
│       ├── data/                 # Room: entities, DAO, repository
│       ├── location/             # GPS, track recording
│       └── di/
├── tools/                        # Desktop pipeline (Python)
│   ├── fetch_tiles.py            # WMTS/WMS → lokální dlaždice pro bbox+zoomy
│   ├── build_pmtiles.py          # dlaždice → PMTiles
│   ├── warp_scan.py              # obálka nad gdalwarp -tps pro skeny ÚAZK
│   ├── dmr5g_hillshade.py        # LAZ → DTM → hillshade → PMTiles (Fáze 5)
│   └── README.md                 # návod: příprava dat pro nový region
├── docs/
│   ├── ARCHITECTURE.md
│   ├── DATA_SOURCES.md           # tabulka výše + stav endpointů
│   └── FIELD_GUIDE.md            # jak používat v terénu
├── .github/workflows/
│   ├── ci.yml                    # build + test na PR
│   └── release.yml               # tag → podepsaný APK do Releases
├── CHANGELOG.md
└── PLAN.md                       # tento dokument
```

**Klíčové principy:**
- Vrstvy jsou data-driven: `layers.json` v app storage definuje seznam vrstev (název, typ: pmtiles/wmts-online, cesta/URL, výchozí opacity, atribuce). Přidání nové mapy = nakopírovat PMTiles + řádek v JSON, žádný release.
- Kalibrace vrstvy = záznam v DB (layer_id, region bbox, transformační matice), aplikuje se za běhu na raster source. Per-region — jedna mapa může mít různé korekce v různých katastrech.
- Vše offline-first: aplikace nikdy nesmí spadnout bez signálu; online zdroje jsou označené a degradují tiše.

---

## 5. Datový model (Room)

```
Find(id, lat, lon, altitude, createdAt, title, category [mince/knoflík/vojenské/…],
     depthCm?, note, favorite, layerContextId?)          // layer = na jaké mapě nalezeno
FindPhoto(id, findId FK, uri, createdAt)                 // 1..3 fotky na nález
Place(id, lat, lon, type [plán/zajímavost/zákaz/sraz], title, note, createdAt, visited)
SearchedArea(id, name, polygonGeoJson, createdAt)        // ručně kreslený polygon prohledané zóny
Track(id, startedAt, endedAt, gpxPath)                   // záznam pochůzky (Fáze 4)
LayerCalibration(id, layerId, bboxGeoJson, matrix[6], createdAt, label)
```

Export: celá DB → GeoJSON + GPX (sdílení do QGIS/Locus), fotky zůstávají v úložišti, export je zip.

---

## 6. Klíčový algoritmus: ruční sladění mapy (kalibrace)

**Režim A — rychlý offset (v terénu):** dvouprstové gesto přímo posouvá/rotuje/škáluje overlay vrstvu (similarity transform, 4 DOF). Tlačítko „uložit pro tuto oblast" → LayerCalibration s bboxem aktuálního viewportu. Při pohybu mapy se aplikuje kalibrace s nejbližším bboxem (nebo žádná).

**Režim B — GCP editor (doma, na tabletu):** split-view stará mapa / ortofoto. Uživatel klepne pár bodů (kostel, křižovatka, rybníkářská hráz) na obou. ≥3 body → afinní transformace (least squares), ≥6 bodů → nabídnout TPS. TPS se v telefonu nepočítá do rastru — vygeneruje se GDAL command / GCP soubor pro `tools/warp_scan.py` a přesný warp proběhne na desktopu. Aplikace tedy TPS jen *připraví*, nepočítá.

**Implementace overlay transformace v MapLibre:** raster source s custom `RasterLayer` + úprava souřadnic přes `ImageSource` corners (pro jednotlivé listy) nebo transformace tile-request souřadnic (pro dlaždicové vrstvy — offset v metrech aplikovaný na bbox requestu). Ověřit v spike issue (#F1-2) co je schůdnější; fallback = render do `ImageSource` se čtyřmi rohy, které gesta přímo hýbou.

---

## 7. Nasazení a provoz

1. **Signing:** vygenerovat keystore, uložit jako GitHub Actions secret (`SIGNING_KEY_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`).
2. **Release flow:** git tag `vX.Y` → workflow `release.yml` sestaví release APK, podepíše, přiloží do GitHub Release.
3. **Instalace:** stáhnout APK z Releases v telefonu; doporučeno nainstalovat **Obtainium** a přidat repo → automatické aktualizace z Releases.
4. **Data:** PMTiles soubory se do repa nekomitují (velikost + licence). Kopírují se do telefonu přes USB/Syncthing do `Android/data/cz.hh.detektormapy/files/layers/`. `tools/README.md` popisuje reprodukovatelnou přípravu.
5. **Zálohy:** export DB (zip s GeoJSON+fotkami) → tlačítko v Settings, uložit do Downloads → uživatel si syncuje sám.

---

## 8. Fáze vývoje (milestones)

- **Fáze 0 — Skeleton & CI** (základ, ~1 den práce Clauda)
- **Fáze 1 — Mapa + offline vrstvy** (MVP mapy)
- **Fáze 2 — Nálezy a místa** (MVP deníku)
- **Fáze 3 — Kalibrace overlay** (klíčová diferenciace)
- **Fáze 4 — Terénní komfort** (tracky, prohledané zóny, ÚAN)
- **Fáze 5 — Vlastní LiDAR render + polish**

Po Fázi 2 je aplikace denně použitelná. Fáze 3 je srdce projektu.

---

## 9. Backlog pro GitHub Issues

> Claude Code: vytvoř milestone pro každou fázi, label `phase:0`…`phase:5`, `type:feat|infra|spike|docs`, prioritu `P1|P2`. Text issue = popis + acceptance criteria níže.

### Fáze 0 — Skeleton & CI
- **F0-1 [infra, P1]** Init Android projektu: Kotlin, Compose, Hilt, Room, MapLibre závislosti; balíček `cz.hh.detektormapy`. *AC: prázdná app s bottom navigation (Mapa / Nálezy / Místa / Nastavení) se zbuildí a spustí.*
- **F0-2 [infra, P1]** GitHub Actions `ci.yml`: assembleDebug + unit testy na každý PR. *AC: zelený běh na PR.*
- **F0-3 [infra, P1]** `release.yml`: na tag podepsaný APK do Releases. *AC: tag v0.0.1 vyprodukuje instalovatelný APK.*
- **F0-4 [docs, P2]** `docs/DATA_SOURCES.md` z tabulky v sekci 3 + skript `tools/check_endpoints.py`, který ověří dostupnost všech služeb (GetCapabilities). *AC: skript vypíše stav všech endpointů.*

### Fáze 1 — Mapa + offline vrstvy
- **F1-1 [feat, P1]** MapScreen s OSM podkladem (online), zobrazení GPS polohy, kompas, follow-mode. *AC: vidím se na mapě, mapa se otáčí dle kompasu.*
- **F1-2 [spike, P1]** Spike: PMTiles raster overlay v MapLibre Android — ověřit čtení lokálního PMTiles (knihovna pmtiles / custom TileProvider) a výkon. Zároveň ověřit proveditelnost runtime offsetu vrstvy (viz sekce 6). *AC: dokument v `docs/` s doporučením + funkční prototyp branch.*
- **F1-3 [feat, P1]** LayerManager: načítání `layers.json`, panel vrstev s checkboxy a sliderem průhlednosti per vrstva, pořadí vrstev. *AC: přepínám II. VM / III. VM / DMR5G nad OSM, opacity funguje, nastavení přežije restart.*
- **F1-4 [feat, P1]** `tools/fetch_tiles.py` + `build_pmtiles.py`: bbox + zoom range + zdroj z DATA_SOURCES → PMTiles. Zvládne WMTS (přímé dlaždice) i WMS (renderované dlaždice, reprojekce z EPSG:5514 do 3857 přes GDAL). *AC: vygeneruju PMTiles pro okres X pro II. VM, III. VM, DMR5G, ortofoto; každý < rozumná velikost; zobrazí se v appce.*
- **F1-5 [feat, P2]** Offline OSM basemap: stáhnout/vygenerovat vector PMTiles ČR (Protomaps build / OpenFreeMap extract) + styl. *AC: letadlový režim → podklad funguje.*
- **F1-6 [feat, P2]** Online WMTS vrstvy jako doplněk (když je signál): DMR5G WMS, ortofoto. *AC: vrstva označená ikonou cloudu, bez signálu tiše zmizí.*

### Fáze 2 — Nálezy a místa
- **F2-1 [feat, P1]** Room schéma dle sekce 5 + repository + migrace baseline. *AC: unit testy DAO.*
- **F2-2 [feat, P1]** FAB „nález": jedno klepnutí → CameraX foto → formulář (kategorie, hloubka, poznámka) → pin na mapě. Celý flow do 15 s. *AC: nález s fotkou a GPS vznikne třemi interakcemi.*
- **F2-3 [feat, P1]** Galerie nálezů: grid s filtrem kategorie/datum, detail s mapkou, hvězdička. *AC: filtr + detail + smazání.*
- **F2-4 [feat, P1]** Místa (waypointy): long-press na mapě → typ + název; odlišné ikony; „navigovat" (bearing + vzdálenost k bodu). *AC: vytvořím plánovanou lokalitu doma, v terénu k ní dojdu.*
- **F2-5 [feat, P2]** Export: zip (GeoJSON nálezy+místa, GPX, fotky) do Downloads; import zpět. *AC: roundtrip export→smazat→import beze ztrát.*
- **F2-6 [feat, P2]** Pin nálezu si pamatuje aktivní historickou vrstvu (layerContextId). *AC: v detailu nálezu vidím, na jaké mapě jsem hledal.*

### Fáze 3 — Kalibrace overlay
- **F3-1 [feat, P1]** Režim A: kalibrační mód — dvouprstové gesto hýbe jen overlay vrstvou (pan/rotate/scale), OSM stojí. Uložení kalibrace pro aktuální bbox, indikátor aktivní kalibrace. *AC: posunutou II. VM sladím na rybník za <30 s, kalibrace se sama aplikuje při návratu do oblasti.*
- **F3-2 [feat, P1]** Správa kalibrací: seznam per vrstva, přejmenování, smazání, výběr při překryvu bboxů. *AC: CRUD funguje.*
- **F3-3 [feat, P1]** Režim B: GCP editor split-view (stará mapa vs. ortofoto), párování bodů, výpočet afinní transformace (least squares) s náhledem, RMSE indikátor. *AC: 4 body → mapa skočí na místo, RMSE zobrazeno.*
- **F3-4 [feat, P2]** Export GCP → soubor pro `tools/warp_scan.py` (gdalwarp -tps) + návod. *AC: sken císařského otisku z ÚAZK projde pipeline a vrátí se jako přesná PMTiles vrstva.*
- **F3-5 [feat, P2]** Import jednotlivého obrázku (PNG/JPG sken) jako ImageSource se čtyřmi tažitelnými rohy — pro rychlé „přiložení" jednoho listu bez pipeline. *AC: stáhnu sken, roztáhnu ho na mapu, uložím rohy.*

### Fáze 4 — Terénní komfort
- **F4-1 [feat, P1]** Záznam pochůzky: foreground service, trail na mapě, GPX uložení, statistiky (čas, km). *AC: 2h záznam nezabije baterku >15 %, trail přežije zamčený displej.*
- **F4-2 [feat, P2]** Prohledané zóny: kreslení polygonu prstem, poloprůhledná výplň, „hotovo/rozpracováno". *AC: vidím, kde už jsem byl.*
- **F4-3 [feat, P1]** Vrstva ÚAN (NPÚ): stáhnout polygony ÚAN I+II pro zájmové kraje do GeoJSON/PMTiles, zobrazit šrafovaně, popup s kategorií. Volitelné varování při vstupu do ÚAN I/II. *AC: v terénu vidím, že stojím v ÚAN II.*
- **F4-4 [feat, P2]** Rychlé akce z lock-screen notifikace při záznamu: „označ nález" (foto později). *AC: pin vznikne bez odemykání.*

### Fáze 5 — LiDAR render + polish
- **F5-1 [feat, P2]** `tools/dmr5g_hillshade.py`: ATOM download LAZ pro zvolené listy → PDAL/GDAL → DTM → multi-directional hillshade + sky-view factor → PMTiles. Výrazně čitelnější než výchozí ČÚZK hillshade. *AC: na známém úvozu je reliéf viditelně ostřejší než WMS DMR5G.*
- **F5-2 [feat, P2]** Widget/zkratka „pre-flight": počasí (open-meteo), východ/západ slunce, poslední kalibrace v okolí. *AC: jedna obrazovka před výjezdem.*
- **F5-3 [docs, P2]** `FIELD_GUIDE.md` + about screen s atribucemi (ČÚZK, CENIA, UJEP/Rakouský státní archiv, OSM, NPÚ). *AC: hotovo.*

---

## 10. Rizika a mitigace

| Riziko | Mitigace |
|---|---|
| MapLibre neumožní runtime offset dlaždicové vrstvy elegantně | Spike F1-2 hned na začátku; fallback ImageSource se 4 rohy (F3-5) je vždy proveditelný |
| Endpointy služeb se změní (CENIA/ČÚZK mění URL historicky často) | `check_endpoints.py` v CI cron 1× měsíčně; data jsou stejně stažená lokálně |
| Velikost PMTiles pro celou ČR | Stahovat po zájmových regionech (okres/kraj), ne celou ČR; DMR5G jen pro lokality |
| EPSG:5514 reprojekce artefakty | Reprojekce vždy v GDAL na desktopu s `-r lanczos`, nikdy v runtime |
| Baterie při záznamu | GPS interval adaptivní (5 s pohyb / 30 s stání), žádný network |
| Právní kontext nálezů | ÚAN vrstva + poznámka ve FIELD_GUIDE (oznámení archeologického nálezu do 2 dnů muzeu/NPÚ, s místem nálezu nemanipulovat 5 dní) |

---

## 11. Definition of Done (globální)

- Kód projde `ktlint` + unit testy v CI.
- Žádný crash bez konektivity (testovat v letadlovém režimu).
- Každá feature ověřitelná na fyzickém zařízení (ne jen emulátor — GPS, kompas, kamera).
- Release notes v CHANGELOG.md.
