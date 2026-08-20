# Changelog

Všechny podstatné změny projektu DetektorMapy jsou zapsané v tomto souboru.

Formát vychází z [Keep a Changelog](https://keepachangelog.com/cs/1.1.0/),
projekt používá [sémantické verzování](https://semver.org/lang/cs/).

Typy změn: `Přidáno`, `Změněno`, `Zastaralé`, `Odebráno`, `Opraveno`, `Bezpečnost`.

## [Nezveřejněno]

Nic — poslední změny jsou v 0.1.0.

## [0.1.0] — nevydáno

První kompletní verze: fáze 0 až 5. Aplikace je ověřená na emulátoru
(Android 16, API 36) — mapa, vrstvy, kalibrace, deník nálezů i záznam pochůzky
běží; 130 unit testů a 88 testů Python pipeline prochází.

### Přidáno

**Fáze 0 — Skeleton & CI**
- Skeleton Android aplikace: Kotlin, Jetpack Compose, Material 3, Hilt, Room,
  MapLibre; balíček `cz.hh.detektormapy`, bottom navigation Mapa / Nálezy /
  Místa / Nastavení. *(F0-1)*
- `ci.yml` — ktlint, `assembleDebug`, `testDebugUnitTest`, `lintDebug` a testy
  Python pipeline na každý PR a push do `main`. *(F0-2)*
- `release.yml` — tag `v*` vyrobí podepsaný APK a přiloží ho do GitHub Release;
  bez secrets vznikne nepodepsaný APK označený jako prerelease. *(F0-3)*
- `docs/DATA_SOURCES.md` + `tools/check_endpoints.py` — ověření dostupnosti všech
  služeb, měsíční cron ve `endpoints.yml`. *(F0-4)*

**Fáze 1 — Mapa a vrstvy**
- MapScreen nad MapLibre: poloha, kompas, follow-mode, zoom, atribuce. *(F1-1)*
- **Vestavěný dlaždicový server na `127.0.0.1`** — výsledek spike F1-2. Čte
  PMTiles v3 (vlastní Kotlin reader) i MBTiles a umí za běhu aplikovat
  kalibrační transformaci, takže kalibrace funguje i pro dlaždicové vrstvy.
- LayerManager nad `layers.json`: panel vrstev, průhlednost, pořadí, stav
  „soubor chybí" u nestažených offline vrstev; nastavení přežije restart. *(F1-3)*
- `tools/fetch_tiles.py` + `build_pmtiles.py` — WMTS/WMS → PMTiles v3
  s reprojekcí 5514 → 3857 přes GDAL na desktopu. *(F1-4)*
- Online vrstvy jako doplněk, označené ikonou cloudu, bez signálu tiše mizí,
  s diskovou cache pro poslední zobrazené dlaždice. *(F1-6)*

**Fáze 2 — Nálezy a místa**
- Room schéma dle PLAN.md sekce 5 + repository vrstva a DAO testy. *(F2-1)*
- FAB „nález": CameraX foto → EXIF GPS → formulář → pin na mapě. *(F2-2)*
- Galerie nálezů s filtrem kategorie/data/oblíbených a detailem. *(F2-3)*
- Místa: long-press na mapě, typy waypointů, navigace (vzdálenost + azimut). *(F2-4)*
- Export/import celého projektu jako zip (GeoJSON + GPX + fotky + manifest),
  idempotentní. *(F2-5)*
- Nález si pamatuje aktivní historickou vrstvu. *(F2-6)*

**Fáze 3 — Kalibrace**
- Režim A: dvouprstové gesto hýbe jen overlay vrstvou, podklad stojí; uložení
  kalibrace pro aktuální bbox. *(F3-1)*
- Správa kalibrací per vrstva: přejmenování, mazání, náhled. *(F3-2)*
- Režim B: GCP editor se split-view stará mapa / ortofoto, afinní i similarity
  fit, živé RMSE a odchylky jednotlivých bodů. *(F3-3)*
- Export GCP do JSON pro `tools/warp_scan.py` (gdalwarp -tps). *(F3-4)*
- Přiložení jednoho skenu jako ImageSource se čtyřmi tažitelnými rohy. *(F3-5)*

**Fáze 4 — Terén**
- Záznam pochůzky: foreground service, adaptivní GPS kadence 5 s / 30 s,
  dávkový zápis, GPX, statistiky. *(F4-1)*
- Prohledané zóny kreslené prstem, se stavem a výměrou v hektarech. *(F4-2)*
- Vrstva ÚAN (NPÚ) + varování při vstupu do chráněného území. *(F4-3)*
- Rychlá akce „Označit nález" přímo z notifikace, bez odemykání. *(F4-4)*

**Fáze 5 — LiDAR a polish**
- `tools/dmr5g_hillshade.py`: ATOM → LAZ → DTM → multi-directional hillshade
  + sky-view factor → PMTiles. *(F5-1)*
- Pre-flight obrazovka: poloha, východ/západ slunce, kalibrace v okolí,
  dostupné vrstvy, baterie, volné místo, počasí. *(F5-2)*
- `FIELD_GUIDE.md` a obrazovka O aplikaci s atribucemi a právním minimem. *(F5-3)*

### Opraveno

Nálezy z revize kódu a bezpečnostního auditu, opravené ještě před vydáním:

- **Směr rotace v Režimu A.** Compose hlásí otočení po směru hodin jako kladný
  úhel v obrazovkovém rámci (y dolů), zatímco transformace pracuje v EPSG:3857
  (y nahoru). Overlay se proto otáčel na opačnou stranu a každá korekce chybu
  zdvojnásobila. Doplněn regresní test.
- **Závod v cache dlaždic.** Vrstva a její generace se četly zvlášť, takže
  dlaždice složená se starou kalibrací mohla skončit pod klíčem nové.
- **Zbytečná invalidace cache.** Generace je nově per vrstva a mění se jen při
  skutečné změně kalibrace; dřív každé zastavení kamery zahodilo celou cache.
- **Idempotence importu.** `externalId` se odvozoval z primárního klíče, který se
  při importu mohl změnit — opakovaný import pak zakládal duplicity. Nově se
  odvozuje z neměnného obsahu řádku.
- **GPS po udělení oprávnění.** ViewModel vzniká dřív než dialog o oprávnění,
  takže se flow poloh uzavřel a poloha nefungovala až do restartu aplikace.
- **Pořadí vrstev.** Vypnutí a zapnutí vrstvy ji přesunulo nad všechny ostatní;
  ÚAN se navíc kreslil přes špendlíky nálezů.
- **Souběh při načítání vrstev.** `reload()` je serializovaný, mapy archivů jsou
  konkurentní a staré archivy se zavírají až po registraci nových.
- **Nafouknutá délka trasy.** GPS jitter při stání se počítal jako ušlá
  vzdálenost; nově se ignoruje pohyb pod prahem přesnosti fixu.
- **Přepínač „nechat displej rozsvícený"** se ukládal, ale nic nedělal.
- Katalog vrstev opraven podle skutečnosti: CENIA publikuje pouze S-JTSK, takže
  II. a III. vojenské mapování jsou offline PMTiles, zatímco ČÚZK a krajské
  ArcGIS služby jdou online přes `MapServer/export` se serverovou reprojekcí.
- Ověřen a opraven endpoint vrstvy ÚAN (`geoportal.npu.cz`); původní odhad
  neexistoval.

### Bezpečnost

- `allowBackup="false"` + `dataExtractionRules` — souřadnice nálezů se už
  nekopírují do cloudové zálohy ani přes `adb backup`.
- `network_security_config.xml` — cleartext povolen výhradně pro loopback,
  jinde je vynucené TLS a důvěřuje se jen systémovým CA.
- Ochrana proti path traversal v cestách vrstev z ručně editovaného `layers.json`.
- PMTiles reader odmítá nesmyslné velikosti z hlavičky, dekompresní bomby
  a příliš velké dlaždice; chyba vrstvy už nemůže shodit aplikaci.
- Import zálohy má stropy na velikost položek (zip bomba).
- Dlaždicový server: omezený počet hlaviček, kratší timeout, bez wildcard CORS.
- CI ověřuje Gradle wrapper; tag v `release.yml` jde přes env a je validovaný.

