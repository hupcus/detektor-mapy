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

## Fronta požadavků (2026-08-20, po vydání 0.1.0)
Pořadí zadané uživatelem. Odškrtávat průběžně.

- [x] **Odebrat varování na chráněnou zónu** — rušilo, protože centrum Úpice je celé ÚAN I,
      takže banner svítil pořád. Odebrán banner i snackbar; **vrstva ÚAN na mapě zůstává**.
      Geometrie (`PolygonIndex.distanceMetersTo` / `nearest`) ponechána i s testy — je to
      otestovaná utilita, hodí se, kdyby se varování mělo vrátit jako volitelné nebo jako
      kartička v pre-flightu.
- [x] **Nemodální posuvník průhlednosti na mapě** (`ui/map/OpacityStrip.kt`) — vlevo dole,
      needimuje mapu. Ovládá nejvyšší viditelný překryv, klepnutím na název cykluje mezi nimi.
      Tažení jde přes `liveOpacity` ve `MapViewModel` a na disk se zapíše až v
      `onValueChangeFinished` — jinak by to bylo ~60 zápisů do DataStore za sekundu.
      Stejnou cestou nově chodí i posuvník v panelu vrstev.
- [x] **Bod aktuální polohy na mapě** — chyběl úplně, byl jen údaj o přesnosti v rohu.
      `MapController.updateLocation()`: kruh přesnosti jako **polygon v metrech** (ne
      `CircleLayer`, jehož poloměr je v pixelech a při zoomu by lhal o přesnosti), modrá tečka
      a kužel směru natočený podle kompasu.
- [x] **Zobrazit/skrýt nálezy na mapě** — perzistence hotová (`LayerPreferences.showFinds`
      / `showPlaces` / `showAreas`, `LayerManager.setShowFinds` …), zbývá stav v `MapUiState`,
      filtrování v `MapController` a přepínače v UI.
- [x] **Nastavení detektoru + rozřazovací systém** — nová sekce v Nastavení: profil detektoru
      a jednoduchý rádce, který podle terénu (les / louka / pole) a počasí (sucho / mokro)
      doporučí, co nastavit. Pozor na poctivost: aplikace nezná konkrétní stroj, takže má
      ukládat **uživatelovy vlastní presety** a doporučovat, který použít, ne vymýšlet
      hodnoty za výrobce.
      Hotovo: `ui/detector/DetectorProfilesScreen.kt` (CRUD nad detektory a presety) a
      `ui/detector/DetectorAdvisorScreen.kt` (rádce). Hodnoty presetů jsou **String, ne Int** —
      každý výrobce škáluje jinak a „18/25“ nebo „auto“ je to, co si člověk reálně zapíše.
      Terén se **nehádá** (offline nemáme data o krajině), jen se pamatuje poslední volba;
      stav půdy je odhad z open-meteo `soil_moisture_3_to_9cm` + `past_days=3`, prahy jsou
      konstanty v `detector/SoilEstimate.kt` a jdou ručně přebít. Řazení presetů je čistá
      funkce `detector/PresetRanking.kt`. Obecná doporučení (`detector/DetectorAdvice.kt`)
      záměrně **neobsahují žádná čísla** — jen co terén a vlhkost dělají s hledáním.
      DB: verze 2, `MIGRATION_1_2` přidává tabulky `detectors` a `detector_presets`
      (aditivní, žádná destruktivní migrace) + `MigrationTest` nad reálnou v1 databází.
      Schémata se kvůli `MigrationTestHelper` přidávají do assetů **jen debug** varianty.
- [x] **Přeorganizovat Nastavení** do záložek/podmenu — teď je to jedna nekonečná nudle.
- [x] **Vydat novou verzi** (v0.2.0) — až bude výše hotové.

## Rozšíření mapových podkladů (2026-08-20) — audit zdrojů a nové vrstvy

Zadání: prověřit císařské otisky po krajích (priorita KHK/Úpice), Müllerovu mapu,
I. VM a indikační skici; zapojit, co jde online. Všechno ověřeno naostro dotazy
(export s `bboxSR=3857&imageSR=3857`, GetCapabilities, reálné dlaždice).

**Klíčové nálezy:**
- **Chartae Antiquae (VÚGTK, `chartae-antiquae.cz/TMS/<id>/{z}/{x}/{y}`)** servíruje
  georeferencované XYZ dlaždice ve **Web Mercatoru**: `MullerC` (Čechy 1720),
  `MullerM` (Morava 1716), `Military1` (I. VM), i Military2/3. Pozor: v cestě je
  „TMS", ale osa Y se NEpřevrací (ověřeno oběma variantami). Prázdné dlaždice
  mimo pokrytí = průhledná PNG ~334 B → empty-tile hranice 400 B platí i tady.
  Pravděpodobně zdroj, který používá KATNA.
- **Karlovarský kraj** má vlastní otisky:
  `geo-ags.kr-karlovarsky.cz/arcgis/rest/services/Image/CisarskeOtisky/MapServer`
  (dynamický, 5514, export do 3857 funguje). Objeveno přes AGOL Experience item —
  krajský web službu nikde neinzeruje.
- **KHK vlastní službu NEMÁ** (portál T-MapServer jen se současnými tématy, hlavní
  GIS odkaz kraje vede na mrtvou stránku). Pro Úpici platí ruční pipeline z ÚAZK
  skenů. Vlastní službu nemají ani Plzeňský, Zlínský, JMK, Praha (vyčteny celé
  REST adresáře); Středočeský/Ústecký/Liberecký/Pardubický/Vysočina/Olomoucký —
  nenalezeno (jen městské aplikace Ústí n. L. a Liberec).
- **Indikační skici = jen skeny** (ÚAZK prohlížeč; MZA web už jen odkazuje do ÚAZK).
  Backend ÚAZK prohlížeče prověřen z JS bundle — žádná georeferencovaná mozaika.
- **mapserver.ujep.cz je mrtvý** (3. timeout). Web oldmaps žije na
  `oldmaps.fzp.ujep.cz` (skeny 1vm/2vm/3vm/mul). Online záloha II. VM: chartae
  `Military2`.
- Bonus: ČÚZK nově má nativní WM cache **`ORTOFOTO_WM`** a **`ZTM_WM`** (LOD 0–23).
  Nezapojeno (nesahat na ověřenou P1 vrstvu), zapsáno v DATA_SOURCES.md.

**Co se zapojilo (katalog v2):** `muller_cechy`, `muller_morava`, `vm1`,
`cisarske_kvk`. Katalog má novou **merge logiku** (`mergeCatalogs` v LayerDef.kt):
při zvednutí verze `DefaultLayers.catalog` se do existujícího `layers.json` doplní
jen nové id, uživatelovy úpravy a mazání zůstávají. Bez toho by nové vestavěné
vrstvy viděly jen čerstvé instalace.

**I. VM v katalogu (F3 návaznost):** nový flag `LayerDef.manualAlignment` — panel
vrstev u vrstvy trvale ukazuje „Přibližná poloha — přesné zarovnání jen ručně
(Přiložit sken…)". Online chartae verze slouží na orientaci, přesné hledání jde
přes Režim A / ImageOverlay, jak bylo rozhodnuto v PLAN.md.

**Pipeline:** nový typ zdroje `xyz` v `tools/sources.py` (šablona bez Capabilities;
probe = konkrétní ověřená dlaždice, `check_endpoints` kontroluje obrázek > 400 B,
`fetch_tiles` stahuje šablonou jako WMTS).

**Past na tomto Macu:** chartae-antiquae.cz vyžaduje TLS 1.3 a `/usr/bin/python3`
má LibreSSL 2.8.3 bez TLS 1.3 → lokální `check_endpoints.py` u chartae zdrojů hlásí
„TLS chyba", i když služba běží (curl v pořádku, dlaždice dekódovány sips jako
256×256 JPEG; `cisarske_kvk` probe lokálně prošel). V CI s OpenSSL projde všechno.

## Druhá vlna rozšíření (2026-08-20 večer) — „rozvíjej dál"

**Rozhodnutí uživatele:** aplikace je **výhradně pro osobní, nekomerční užití**
(čestné prohlášení v konverzaci). Veřejné šíření se teď neřeší; issue #34 zůstává
otevřené jen pro případ, že by se to v budoucnu změnilo. Chartae/ČÚZK/krajské
služby se tedy používají ve stejném režimu jako dosud CENIA — lokálně, s atribucí.

**Nové vrstvy v katalogu (pořád verze 2 — v1→v2 ještě nevyšlo):**
- `vm2_online` (chartae Military2, z5–16) — okamžitá online náhrada CENIA PMTiles
  a mrtvého `ii_vm_ujep`.
- `vm3_topo` (chartae Military3, z5–16) — **topografické sekce III. VM 1:25 000**,
  podrobnější než speciálky; CENIA je vůbec nemá. Názvy vrstev ověřeny
  z konfigurace chartae porovnávače (`Military3` = 1:25 000, `Military3_75` =
  speciálky jen do z15, `Military2_144` = speciální mapy II. VM).
- `ztm` — druhý basemap: ZTM_WM cache ČÚZK (z4–19).
- `ortofoto` přepnuto z ARCGIS exportu na `ORTOFOTO_WM` XYZ cache (z7–20).
  Pozor: ArcGIS cache šablona je `/tile/{z}/{y}/{x}` — **řádek před sloupcem**
  (hlídá to test v test_tools i CatalogMergeTest).

**`tools/archiv_fetch.py` — skeny stabilního katastru z Archivu ÚAZK.** Reverse
z veřejné aplikace `ags.cuzk.gov.cz/archiv`:
- token vydává anonymní GP job `arcgis2/.../GenerateToken` (referer archiv appky,
  parametr prázdný, zpráva „Token je: …"); služby žijí na **arcgis4**
  (`/Archiv/klady/MapServer` vrstva 3 + ImageServer `archiv_nespojene_stable`).
- ImageServer hlásí SR 5514, ale souřadnice jsou **pixelový rám** → skeny nejsou
  georeferencované; export přes `lockRaster`, limit 15000×4100 px/request,
  `--full` stahuje pruhy a lepí GDALem (ověřeno: list 4 Úpice 7695×10927 px
  slepený beze švů).
- Ověřeno na Úpici: `--katastr Úpice` → 5 skenů COC 1840 („Markt Eipel"),
  vizuálně zkontrolováno. Série: `cio` (otisky), `om` (originální mapy),
  `kme` (evidenční).
- GDAL 3.13.3 je od teď na stroji nainstalovaný (brew).

**Ověření na emulátoru (API 36):** merge katalogu ověřen naostro — podstrčený
v1 `layers.json` po studeném startu povýšil na v2 (15 vrstev, uživatelský
`ortofoto` zachován jako arcgis, `manualAlignment: true` u vm1 se serializuje).
Všech 5 nových online vrstev servíruje reálné dlaždice přes `LocalTileServer`
(ověřeno `adb forward` + curl; prázdná dlaždice mimo pokrytí KVK má ~203 B).

**Pasti na příště (emulátor):**
- `adb install` debug APK = balíček `cz.hh.detektormapy.debug` — jiný adresář
  `files/layers` než release! Čtení release souboru vypadá jako „merge neběží".
- Po `force-stop` může úloha zůstat „viset" za dialogem runtime permissions
  (GrantPermissionsActivity) — `am start` pak hlásí OK, ale proces aplikace
  vůbec nevznikne. Řešení: `pm grant` oprávnění přes adb + start s
  `--activity-clear-task`.
- Spouštět emulátor na pozadí **bez** `| head` — pipe po zavření zabije emulátor
  SIGPIPE.

**Licence → issue #34:** JčK výslovně zamezuje obchodní užití (ochranné znaky
ČÚZK); VÚGTK/chartae a KV kraj podmínky nepublikují — před veřejným šířením nutno
oslovit. Zapsáno do issue.

## Presety Nokta The Legend (v0.2.1)
Zdroj pravdy je `docs/nokta-legend-presety.md` — dokument majitele, ne tovární nastavení.
Z něj je vygenerovaný `detector/NoktaLegendPresets.kt` (6 profilů: les/louka/pole × běžné/mokro).

**Když se dokument změní, přegeneruj Kotlin, needituj ho ručně** — jinak se obojí rozejde.
Generátor parsuje nadpisy `# PROFIL n – NÁZEV` a sekce `## Nastavení` / `## Proč` /
`## Doladění v terénu`; vodorovné čáry `---` je nutné vyhodit, jinak se propíšou jako odrážka.

Soubor má v `.editorconfig` vypnuté `max-line-length` — je to přepsaná datová tabulka, ne kód,
a lámat české věty kvůli sloupci 120 by jen znesnadnilo porovnání se zdrojem.

Nasazení do knihovny dělá `DetectorRepository.seedNoktaLegend()`, které je **idempotentní podle
jména detektoru**, takže tlačítko jde zmáčknout opakovaně bez následků.
