# handoff.md — DetektorMapy

> Živý předávací dokument. Sem se zapisují **rozhodnutí, stav a otevřené body**.
> Aktualizuje se po každé dokončené fázi. Zadání = `PLAN.md`.

## Identita projektu
- GitHub: `hupcus/detektor-mapy` (private)
- Lokální cesta: `/Users/hupcus/Documents/VIBE-CODE/DETECT`
- Package: `cz.hh.detektormapy`
- Jediný uživatel, žádný Play Store, distribuce sideload APK z Releases.

## Toolchain (závazný)
| Věc | Verze | Pozn. |
|---|---|---|
| JDK pro build | **21** (`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) | systémové Java 25/26 AGP nepodporuje → `org.gradle.java.home` v `gradle.properties` |
| Gradle wrapper | **8.14.3** | Gradle 9.6 odstranil interní API, které AGP 8.x používá → wrapper musí být 8.x |
| AGP | **8.13.2** | AGP 9 zrušil samostatný `kotlin-android` plugin (built-in Kotlin) a Hilt/KSP ekosystém na tom zatím není → zůstáváme na stabilní 8.x |
| Kotlin | 2.3.21 | Compose compiler plugin je součást Kotlinu |
| KSP | 2.3.11 | Room + Hilt |
| compileSdk / target | 36 | minSdk 26 |
| MapLibre | `org.maplibre.gl:android-sdk:11.11.0` | + `android-plugin-annotation-v9:3.0.2` |
| Compose BOM | **2026.06.01** | 2026.08 (Compose 1.12) vyžaduje compileSdk 37 + AGP 9.1 |
| Hilt | **2.57.2** | 2.58+ vyžaduje AGP 9.0+ |
| Room | 2.8.4 | |
| CameraX | 1.6.1 | |

Android SDK: `~/Library/Android/sdk` (platformy 31–36, build-tools 34/35/36).

## Klíčové architektonické rozhodnutí — lokální dlaždicový server
Toto je odpověď na spike **F1-2** a zároveň základ pro **F3-1** (kalibrace).

MapLibre Android nemá runtime transformaci raster vrstvy ani spolehlivé nativní čtení
PMTiles napříč verzemi. Řešení: **vestavěný HTTP server na `127.0.0.1`**, který:
1. čte dlaždice přímo z lokálního `.pmtiles` archivu (vlastní Kotlin reader, žádná závislost),
2. za běhu aplikuje **afinní kalibraci** vrstvy (posun/rotace/měřítko) tím, že výslednou
   dlaždici složí Canvas matricí ze zdrojových dlaždic v okolí 3×3,
3. MapLibre vidí obyčejný `RasterSource` s URL `http://127.0.0.1:<port>/t/{layerId}/{z}/{x}/{y}`.

Důsledky: kalibrace funguje i pro dlaždicové vrstvy (ne jen ImageSource), je čistě
lokální, nezávislá na verzi MapLibre a testovatelná unit testy bez zařízení.

Fallback pro jednotlivé skeny (F3-5): `ImageSource` se 4 tažitelnými rohy.

## Souřadnice
Runtime = výhradně Web Mercator (EPSG:3857). Reprojekce z S-JTSK (EPSG:5514) **jen**
v desktop pipeline (GDAL, `-r lanczos`). V appce nikdy.

## Stav fází
- [x] Fáze 0 — Skeleton & CI (Gradle, CI/release/endpoints workflows, docs, 28 issues)
- [x] Fáze 1 — Mapa + offline vrstvy (MapScreen, LayerManager, PMTiles reader, tile server, tools)
- [x] Fáze 2 — Nálezy a místa (Room, CameraX capture, galerie, waypointy, export/import)
- [x] Fáze 3 — Kalibrace overlay (Režim A gesta, správa kalibrací, GCP editor, image overlay)
- [x] Fáze 4 — Terénní komfort (track service, prohledané zóny, ÚAN pipeline)
- [x] Fáze 5 — LiDAR render + polish (dmr5g_hillshade.py, pre-flight, about/atribuce)

## Mapa modulů — kdo co dělá
| Balíček | Obsah |
|---|---|
| `map/` | `LayerManager` (katalog + registrace archivů), `LocalTileServer`, `CalibratedTileComposer`, `TileWarpGeometry`, `pmtiles/` (PMTiles v3 + MBTiles reader), `WmsTileRenderer` (+ `XyzTileArchive`, `WmsTileArchive`) |
| `calibration/` | `Affine2D` — jediné místo, kde se počítá transformace (3857 metry, ne pixely) |
| `data/` | Room entity/DAO/repository, `export/` (zip + GeoJSON + GPX), `AppDirectories` |
| `location/` | `LocationProvider` (platformní LocationManager, ne fused — funguje v letadlovém režimu), `CompassProvider`, `TrackRecordingService` + `TrackRecorder` + `GpxWriter` |
| `ui/map/` | `MapScreen`, `MapController` (inkrementální sync stylu), `LayerPanel`, `MarkerIcons` (bitmapy místo glyphů → žádná síť) |
| `ui/calibration/` | Režim B: `GcpEditorScreen` (split view), `ImageOverlayScreen` (4 rohy), `CalibrationViewModel` |
| `ui/finds`, `ui/places`, `ui/settings` | deník, waypointy, nastavení, export, pre-flight, about |

## Proč `MarkerIcons` kreslí bitmapy
MapLibre umí text v `SymbolLayer` jen s glyph endpointem = síťová závislost. Offline-first
aplikace si to nemůže dovolit, takže se špendlíky vykreslí na `Bitmap` a registrují přes
`style.addImage`.

## Ověřeno na zařízení (emulátor API 36, 2026-08-20)
- Aplikace nastartuje, mapa vykreslí OSM podklad přes lokální tile server.
- Ortofoto (ArcGIS `export`) i DMR 5G (WMS) se vykreslí a sedí s podkladem.
- Zápis nálezu: CameraX foto → EXIF GPS → formulář → záznam v DB → pin na mapě
  → karta v galerii. Nález si zapamatoval aktivní vrstvu (F2-6).
- Panel vrstev poctivě hlásí „soubor chybí" u nestažených offline vrstev.
- 130 unit testů (JVM + Robolectric) a 88 testů Python pipeline prochází, ktlint čistý.

## Co ještě NENÍ hotové / vědomé kompromisy
- **F1-5 offline vektorový OSM podklad** — v katalogu je zatím jen online OSM raster.
  Pro plný offline režim je potřeba vygenerovat vektorové PMTiles ČR a přidat je jako
  `LayerKind.VECTOR` (renderuje se přímo stylem, ne přes tile server).
- **PMTiles pro historické mapy nejsou v repu** (velikost + licence). Bez nich se
  II./III. vojenské mapování v appce nezobrazí — panel to říká nahlas.
- **GDAL/PDAL nejsou na tomto stroji**, takže `fetch_tiles.py` → `build_pmtiles.py`
  a `dmr5g_hillshade.py` jsou otestované proti mockům, ne plným během.
  `brew install gdal pdal` a pak podle `tools/README.md`.
- **Nižší priorita z revize:** až 25 sériových HTTP fetchů na jednu warpovanou dlaždici
  online vrstvy (offline problém není); reorder vrstev tažením není v UI vystavený.

## Otevřené body / rizika
- ~~Endpoint ÚAN~~ **vyřešeno**: `https://geoportal.npu.cz/arcgis/rest/services/Tematicke/CP_UAN/MapServer`,
  EPSG:5514, vrstvy 0=ÚAN I, 1=ÚAN II, 2=pásmo II, 3=ÚAN IV. Ověřeno staženými polygony.
- Záložní zdroj `ii_vm_ujep` (mapserver.ujep.cz) neodpovídá do 15 s. Je to P2 záloha,
  primární CENIA běží — ale kdyby CENIA vypadla, je nutné ověřit, zda UJEP ještě žije.
- PMTiles data se **nekomitují** do repa (velikost + licence). Kopírují se do
  `Android/data/cz.hh.detektormapy/files/layers/`.
- GDAL není na tomto stroji nainstalován → desktop pipeline je napsaná a otestovaná
  proti mockům, plný běh vyžaduje `brew install gdal`.

## Log rozhodnutí (chronologicky)
- **2026-08-19** Gradle wrapper 8.14.3 nelze vygenerovat systémovým Gradle 9.6.1 (padne při
  evaluaci projektu na AGP 8.x) → wrapper bootstrapnut v prázdném adresáři a zkopírován.
- **2026-08-19** GitHub: repo `hupcus/detektor-mapy` (private) + 12 labelů + 6 milestones +
  28 issues (PLAN.md sekce 9 má reálně 28 položek, ne 21). `origin` nastaven, nic nepushnuto.
- **2026-08-20** Hilt musel dolů na 2.57.2 — 2.58+ vyžaduje AGP 9.0+.
- **2026-08-20** Compose BOM dolů na 2026.06.01 — 2026.08 (Compose 1.12) chce compileSdk 37,
  na stroji je nejvýš android-36.
- **2026-08-20** Spike F1-2 uzavřen ve prospěch lokálního HTTP serveru (viz výše). PMTiles v3
  reader je vlastní, čistě v Kotlinu, bez závislosti.
- **2026-08-20** Ověřeno naostro proti službám: CENIA (II./III. VM) publikuje **výhradně**
  EPSG:5514 — WMTS má jediný TileMatrixSet `jtsk:epsg:5514`, WMS ohlašuje jen `SRS=EPSG:5514`.
  Online je tedy použít nelze; v katalogu jsou jako offline PMTiles. Naproti tomu ArcGIS
  `MapServer/export` s `bboxSR=3857&imageSR=3857` reprojektuje na serveru → nový
  `LayerKind.ARCGIS` pro ortofoto a císařské otisky. DMR 5G WMS umí `CRS=EPSG:3857` přímo.
- **2026-08-20** `targetSdk 36` blokuje cleartext HTTP, takže MapLibre nedosáhl na vlastní
  tile server na `127.0.0.1`. Řešeno `network_security_config.xml` s výjimkou **jen** pro
  loopback; zbytek sítě má vynucené TLS.

## Panel k prolínání vrstev (2026-08-20) — závěry

Tři nezávislá stanoviska (UX, MapLibre technika, kritik s rešerší). Shodli se, a to
proti původnímu zadání.

**Swipe závěs a lupa jsou vetované, ne odložené.**
- Měření: Lobo/Pietriga/Appert, CHI 2015 — na *přesně této* úloze (ortofoto vs. mapa,
  hledání změn) vyšel Translucent Overlay nejlépe a **Swipe špatně**, s doporučením
  jeho používání přehodnotit.
- Technicky: MapLibre nemá pro `RasterLayer` žádný clip ani masku (ověřeno javapem nad
  11.11.0: žádný `setFilter`, žádný `clip` typ vrstvy, výrazy neznají souřadnice
  obrazovky, `Style` nemá `moveLayer`). Kanonická implementace = **dvě instance MapView**
  → dva GL kontexty, dvojí pyramida dlaždic, dvojí baterie.
- Ergonomicky: dělítko se tahá druhou rukou, kterou drží detektor.

**Radiální menu: nestavět.** Rozhodující argument není dosah palce, ale že *do radiály
nedáš slider průhlednosti* — cesta by byla radiála → panel → slider, tedy tři úrovně
místo dvou. Rodek je vlastní důkaz: „ZOBRAZIT" na liště a „VIEW" v oblouku se stejnou
ikonou oka = rozpadlá hierarchie, do které radiála spadne, jakmile položek přeteče.

**Skutečná vada, kterou máme:** `LayerPanel` je `ModalBottomSheet` se scrimem, takže
*nevidíš efekt slideru, kterým zrovna hýbeš*. Locus má na tohle request s 20 hlasy a
v Locus Map 4 to přepracoval.

**Doporučené pořadí prací:**
1. Peek — podržení tlačítka Vrstvy zprůhlední overlay, puštění vrátí. ~15 řádků.
2. Nemodální pruh s posuvníkem na mapě místo sheetu se scrimem.
3. Až potom případně plný „Prolínač" s detenty a letopočty.

**Dvě tvrdá technická omezení, která musí každá implementace respektovat:**
- Prolínání smí sahat **výhradně na `rasterOpacity`**, nikdy na viditelnost vrstvy —
  `syncRasterLayers` při vypnutí odstraní layer i source, takže návrat = refetch celého
  výřezu, a `LocalTileServer` posílá `Cache-Control: no-store`. Pro peek je naopak
  `rasterOpacity(0f)` správně a je zadarmo (paint property, jen GPU).
- Prolínání **nesmí** téct přes `LayerPreferences`/DataStore — při tažení by to bylo
  ~60 zápisů na disk za sekundu. Potřebuje vlastní netrvalý stav ve `MapViewModel`,
  který se s uloženou opacity jen násobí.

**Známé chyby k opravě, až se bude sahat na vrstvy:**
- `MapController.syncRasterLayers` počítá kotvu pořadí z katalogového `def.order`
  a ignoruje uživatelský override z `LayerPreferences`.
- `MapScreen` má prázdný `onDispose` u `DisposableEffect(mapView)` → listenery mapy se
  neodregistrovávají.
- `nearest()` v `PolygonIndex` je lineární sken. Pro kraj v pohodě; pro ÚAN celé ČR by
  to byl sken při každém fixu (12×/min při adaptivní kadenci) — pak chce prostorový index.

## Převzato z konkurence (Rodek / Metal Detecting Hub)
- **Varování při přiblížení k chráněné zóně** — hotovo, `APPROACH_WARNING_M = 250`.
  Jsme na tom líp než oni: jejich je kartička na pre-flightu, naše je živý banner na mapě.
- **Vlhkost půdy** do pre-flightu — ověřeno, že open-meteo (už ho voláme) vrací
  `soil_moisture_3_to_9cm` a `past_days=3` dá „nedávno". Jeden parametr navíc v URL.
  Formulovat jako orientaci, ne predikci hloubky: vlhká málo mineralizovaná půda hloubce
  pomáhá, nasycená a mineralizovaná ji sráží.
- **„Tvůj lov: 2. na tomto místě"** — jeden dotaz nad Room, nejlepší poměr hodnota/práce.

## Náhled reality (peek) — hotovo 2026-08-20
Podržení tlačítka Vrstvy zprůhlední historické překryvy, puštění je vrátí. Klepnutí dál
otevírá panel vrstev.

Dvě věci, na které se narazilo a stojí za zapamatování:
- **`SmallFloatingActionButton` požírá dotyky.** Má vlastní `clickable`, které spotřebuje
  pointer stream dřív, než ho uvidí `Modifier.pointerInput` na rodiči — dlouhý stisk se tedy
  nikdy nespustil. Tlačítko je proto obyčejný `Surface` s vlastním `detectTapGestures`.
- **`adb shell input` neumí věrohodně simulovat podržení.** Ani `swipe` s dlouhým trváním,
  ani ruční `motionevent DOWN/MOVE/UP` nepřekročí práh dlouhého stisku — gesto se doručí
  časově zkomprimované a vždy z něj vyjde obyčejné klepnutí. Ověřovat gesta přes adb je
  slepá ulička; použij instrumentovaný Compose test s `performTouchInput { longClick() }`.

Proto vznikl první androidTest: `app/src/androidTest/.../ui/map/MapOverlayControlsTest.kt`
(3 testy: podržení zapne a pustí, klepnutí otevře panel bez náhledu, bez viditelného
překryvu podržení nedělá nic). Spouští se `./gradlew :app:connectedDebugAndroidTest` proti
běžícímu emulátoru — **v CI zatím není**, chtělo by to emulátor v pipeline.

Pozor: emulátoru dojde místo, když se do něj nahrají PMTiles i APK; `INSTALL_FAILED_INSUFFICIENT_STORAGE`
se řeší smazáním `/sdcard/Android/data/<pkg>/files/layers`.
