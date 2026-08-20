# Changelog

Všechny podstatné změny projektu DetektorMapy jsou zapsané v tomto souboru.

Formát vychází z [Keep a Changelog](https://keepachangelog.com/cs/1.1.0/),
projekt používá [sémantické verzování](https://semver.org/lang/cs/).

Typy změn: `Přidáno`, `Změněno`, `Zastaralé`, `Odebráno`, `Opraveno`, `Bezpečnost`.

## [Nezveřejněno]

### Přidáno
- **Sedm nových online mapových vrstev** (vše ověřeno naostro 2026-08-20):
  **Müllerova mapa Čech (1720)** a **Moravy (1716)**, **I. vojenské mapování
  (1764–68)**, **II. vojenské mapování online** a **III. vojenské mapování
  1:25 000** (topografické sekce — podrobnější než speciálky, jinde nedostupné)
  z Virtuální mapové sbírky Chartae-antiquae.cz (VÚGTK), **Císařské otisky —
  Karlovarský kraj** z krajského ArcGIS serveru a **Základní topografická mapa
  ČÚZK** jako druhý podklad vedle OSM.
- **`tools/archiv_fetch.py`** — stažení skenů stabilního katastru (císařské
  otisky, originální mapy, evidenční mapy) z Archivu ÚAZK jedním příkazem podle
  názvu katastru nebo souřadnice, včetně plného rozlišení. Ověřeno na katastru
  Úpice (5 skenů, 1840).
- **I. vojenské mapování je poctivě označené jako přibližné** — mapovalo se od oka,
  takže vrstva nese nový příznak `manualAlignment` a panel vrstev u ní trvale
  ukazuje, že přesné zarovnání se dělá ručně přes „Přiložit sken…".
- **Katalog vrstev se umí doplňovat** — nové vestavěné vrstvy se při aktualizaci
  aplikace přidají i do existujícího `layers.json`, aniž by přepsaly ruční úpravy.
- Desktop pipeline zná nový typ zdroje `xyz` (prosté `{z}/{x}/{y}` šablony bez
  Capabilities) včetně kontroly dostupnosti přes reálnou dlaždici.

### Změněno
- **Ortofoto jede z nativní Web Mercator cache ČÚZK** (`ORTOFOTO_WM`) místo
  serverového přepočtu z S-JTSK — načítá se rychleji a nově až do zoomu 20.
  Platí pro čerstvé instalace; existující `layers.json` si ponechá starou
  (funkční) definici.
- Aplikace se v telefonu jmenuje **„Detektor mapy“** (s mezerou) místo „DetektorMapy“.
  Změnil se jen zobrazovaný název; identifikátor balíčku `cz.hh.detektormapy` zůstává, jinak
  by z toho byla nová aplikace a přišel bys o databázi nálezů. Technické značky uvnitř
  exportů (`creator` v GPX, `app` v manifestu zálohy, User-Agent) také zůstávají — jsou to
  identifikátory formátu, ne popisky.

## [0.2.1] — 2026-08-20

### Přidáno
- **Presety pro Nokta The Legend** — šest hotových profilů pro les, louku a pole, každý ve
  variantě za běžných podmínek a za mokra, včetně kompletního nastavení (frekvence, Recovery,
  IF, St, dt, AG, tóny), zdůvodnění a doladění v terénu. Načtou se jedním tlačítkem a jsou to
  **vlastní ověřená nastavení majitele**, přepsaná z `docs/nokta-legend-presety.md` — ne tovární
  presety Nokty a ne nic, co by si aplikace vymyslela.
- **Startovací rutina na nové lokalitě** v rádci: Noise Cancel, Ground Balance, projít 20–30 m
  a teprve pak sahat na čísla.

### Odebráno
- **Obecná doporučení** v rádci. Nahradily je konkrétní presety pro reálný stroj, což je přesně
  ta věc, kterou obecné rady nikdy nedokázaly nahradit.

## [0.2.0] — 2026-08-20

Terénní kolo oprav a doplňků po prvním nasazení na telefon.

### Přidáno
- **Bod aktuální polohy na mapě** — modrá tečka, prstenec přesnosti a kužel směru podle
  kompasu. Dřív byl jen údaj o přesnosti v rohu, takže dohledat křížek nebo zbourané
  stavení podle staré mapy byla loterie. Prstenec je polygon v metrech, ne kolečko
  v pixelech, aby při zoomu nelhal o přesnosti.
- **Posuvník průhlednosti přímo na mapě** — vlevo dole, nic neztmavuje. Panel vrstev je
  modální a zakrývá půlku mapy, takže při tažení posuvníku nebylo vidět, co dělá.
  Klepnutím na název se přepíná mezi viditelnými překryvy.
- **Náhled reality** — podržení tlačítka Vrstvy zprůhlední historické mapy, puštění je
  vrátí. Řešeno průhledností, ne viditelností: vypnutí vrstvy odstraní i zdroj, takže
  návrat by znamenal znovu stáhnout celý výřez.
- **Nálezy, místa a prohledané zóny jdou skrýt** (sekce „Na mapě“ v panelu vrstev).
- **Sekce Detektor** — vlastní detektory, cívky a presety, plus rádce, který podle terénu
  a odhadnutého stavu půdy seřadí *tvoje* presety a vysvětlí proč. Aplikace zásadně
  nevymýšlí hodnoty za výrobce: obecná pravidla jsou zvlášť a označená jako orientační.
  Stav půdy odhaduje z open-meteo (`soil_moisture_3_to_9cm` + srážky za 3 dny) a přiznává,
  že jde o model na síti ~11 km, ne o měření. Ruční přebití je vždy možné.
- **Nastavení má čtyři záložky** (Mapa / Detektor / Data / Ostatní) místo jednoho
  nekonečného seznamu.

### Opraveno
- **Kalibrace ukazovala prázdné panely.** Kamera byla natvrdo uprostřed republiky, rastrový
  zdroj se připojoval jen při první kompozici (kdy dlaždicový server ještě neběžel) a
  dorovnání podle bboxu shodilo zoom pod minimum offline vrstvy. Editor se teď otevře tam,
  kde máš mapu, ve stejném měřítku.
- **Stahování dlaždic zabíralo 8× víc místa, než mělo.** CENIA servíruje JPEG, ale zdroj byl
  deklarovaný jako PNG, takže ho gdalwarp překódovával — 155 kB místo 20 kB na dlaždici.
- **Průhlednost se zapisovala na disk při každé změně**, tedy zápis do DataStore každý
  snímek tažení. Nově se ukládá až po puštění.
- **Kužel směru se vykresloval vedle bodu polohy** místo z něj (špatná kotva ikony).
- Název vydání hlásil „nepodepsáno“ i u podepsaného APK.

### Odebráno
- **Varování při vstupu do ÚAN.** Bylo správné, ale nepoužitelné: centrum Úpice je celé
  ÚAN I, takže banner svítil pořád. Vrstva ÚAN na mapě zůstává, mizí jen to vyskakování.

### Bezpečnost
- Databáze má migraci 1 → 2 s testem, který ověřuje, že upgrade zachová existující data.
  Ověřeno i naostro: nález založený v 0.1.0 přežil instalaci podepsané 0.2.0 přes ni.

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

