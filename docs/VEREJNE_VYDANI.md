# Fáze 7 — Veřejné vydání: detailní zadání

> Cíl: z osobní aplikace udělat **zdarma aplikaci pro všechny fanoušky detektorů**,
> kterou si člověk bez znalostí IT stáhne, hned ví, jak ji použít, mapy mu fungují
> kdekoli v ČR a co jednou viděl online, má navždy offline.
>
> Zadáno 2026-08-21. Vychází ze stavu v0.4.0 (15 vrstev v katalogu, z toho 13 online
> pro celou ČR; kalibrace funkční a ověřená naostro).

## Proč přepisujeme část fáze 6

Původní F6 (issues #29–#33) předpokládala, že offline data vznikají **stahováním
z CENIA v S-JTSK**, a proto začínala reprojekcí Křováka v mobilu (#29). Výzkum
zdrojů (viz `docs/DATA_SOURCES.md`) mezitím našel **online zdroje ve Web Mercatoru
pro všechna tři vojenská mapování i Müllera** (chartae-antiquae.cz), takže:

- **#29 (reprojekce S-JTSK v mobilu) není pro veřejné vydání potřeba** — všechny
  vrstvy, které chceme veřejně nabízet, už server posílá v EPSG:3857. Issue zůstává
  otevřené jako P3 pro hypotetické budoucí S-JTSK-only zdroje, ale nic na něm nestojí.
- Těžiště se přesouvá z „stáhni a konvertuj na desktopu" na **write-through cache
  přímo v telefonu**: každá dlaždice, kterou uživatel jednou viděl, se uloží a už
  se nikdy nestahuje znovu.

Pořadí prací je záměrné: **A1 (cache) odemyká všechno ostatní** a je nejmenší kus
s největším dopadem. Právní blok C běží paralelně, protože čekání na odpovědi
institucí trvá týdny.

---

## Blok A — Offline: „co jsem viděl, mám navždy"

### A1 — Write-through dlaždicová cache v LocalTileServer  *(nové, nahrazuje jádro #30)*

**Popis.** `LocalTileServer.serveTile()` dnes drží dlaždice jen v paměťové
`TileByteCache` (64 MB, zmizí s procesem) a posílá `Cache-Control: no-store`.
Doplnit trvalou vrstvu: po úspěšném stažení online dlaždice ji zapsat do MBTiles
souboru vrstvy (`layers/<id>.cache.mbtiles`), při dalším dotazu ji vzít odtamtud.

**Návrh řešení.**
- Zapisovač MBTiles podle acceptance criteria #30 (transakční dávky, TMS flip
  řádků, `metadata` tabulka) — čtečka `MbTilesReader` už existuje, formát je daný.
- Pořadí čtení pro online vrstvy (`XYZ`/`ARCGIS`/`WMS`): paměť → cache.mbtiles →
  síť; po síti zapsat do obou. Offline vrstvy (`PMTILES`/`MBTILES`) se nemění.
- Zápis mimo request-thread (fronta + jeden zapisovací thread), aby latence
  servírování nevzrostla. Ztráta zápisu při pádu je přijatelná — dlaždice se
  příště stáhne znovu.
- **Kalibrace se do cache nesmí propsat**: cachovat *zdrojové* bajty (před
  `CalibratedTileComposer`), ne výstup. Klíč je `(z, x, y)` bez generace.
- Empty-tile pozor: 204/prázdné odpovědi necachovat trvale (mimo pokrytí dnes
  může být pokryté zítra po rozšíření služby) — nebo cachovat s TTL.
- Vypínač v Nastavení („Ukládat mapy pro offline použití", default zapnuto)
  + per-vrstva výjimka pro ortofoto (velké dlaždice, malý historický přínos).

**Acceptance criteria**
- [ ] Zapnu vrstvu online, projdu si oblast, zapnu letadlový režim, restartuji
      aplikaci → stejná oblast je vidět ve všech navštívených zoomech.
- [ ] Soubor `.cache.mbtiles` přečte desktopový `sqlite3` i `MbTilesReader`.
- [ ] Kill procesu uprostřed zápisu nezanechá poškozený soubor (WAL/transakce).
- [ ] Kalibrovaná vrstva servíruje z cache správně zdeformované dlaždice
      (tzn. cache drží originál a warp probíhá až při servírování).
- [ ] Latence servírování cache-hit < 10 ms (lokální měření v testu).
- [ ] Unit testy zapisovače: round-trip s `MbTilesReader`, přerušený zápis,
      dvojí zápis téže dlaždice (idempotence).

**Soubory:** `map/LocalTileServer.kt`, nový `map/pmtiles/MbTilesWriter.kt`,
`map/LayerManager.kt`, `ui/settings/SettingsScreen.kt`.
**Odhad:** M (2–3 dny). **Závislosti:** žádné. **Priorita: P1 — dělat první.**

### A2 — Stáhnout oblast dopředu  *(= #31, upřesnění)*

**Popis.** Beze změny proti #31 (obdélník prstem, odhad velikosti, strop z15,
potvrzení nad 500 MB) s jedním doplněním: **cílem zápisu je tatáž cache z A1**,
žádný zvláštní formát. „Stáhnout oblast" je jen hromadné naplnění téže cache,
kterou plní i běžné prohlížení — jeden kód, jedna pravda.

**Navíc oproti původnímu zadání**
- [ ] Rychlá akce „Stáhnout aktuální výřez" přímo z panelu vrstev (bez kreslení).
- [ ] Odhad ukazuje i **kolik už z oblasti v cache je** („stáhne se 1 240 z 3 600").

**Odhad:** M. **Závislosti:** A1.

### A3 — Stahovací služba na pozadí  *(= #32, beze změny zadání)*

Foreground service, notifikace, retry, pokračování, rate limit. Vzor:
`location/TrackRecordingService.kt`. Doplnění jediné: **User-Agent
`DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`** posílat u *všech*
síťových dotazů na dlaždice, ne jen při hromadném stahování — slušnost vůči
státním službám je podmínka veřejného vydání (viz C1).

**Odhad:** L. **Závislosti:** A1, A2.

### A4 — Správa úložiště  *(nové)*

**Popis.** Obrazovka v Nastavení: seznam vrstev s velikostí cache + stažených
archivů, tlačítka „Smazat cache vrstvy" a „Smazat vše", celkový součet a volné
místo v telefonu. Bez toho si cache časem vezme gigabajty a uživatel nemá páku.

**Acceptance criteria**
- [ ] Velikosti odpovídají skutečnosti (stat souborů, ne odhad).
- [ ] Smazání cache nevyžaduje restart aplikace (unregister + delete + re-register).
- [ ] Při < 500 MB volného místa aplikace přestane cache plnit a řekne to.

**Odhad:** S. **Závislosti:** A1.

### A5 — Offline OSM podklad  *(= #9, povýšení priority)*

**Popis.** Beze změny technického zadání (vector PMTiles + MapLibre styl), ale
**pro veřejné vydání povyšuje z P2 na P1** ze dvou důvodů:

1. **Právní**: usage policy tile.openstreetmap.org veřejně distribuovaným
   aplikacím **zakazuje** používat oficiální dlaždicový server jako výchozí
   podklad bez předchozího svolení. Osobní použití bylo OK; veřejná appka s tímhle
   defaultem je porušení pravidel od prvního dne. (Ověřit aktuální znění — úkol C1.)
2. **UX**: podklad je jediná vrstva, bez které je mapa k ničemu; detektorář bez
   signálu v lese potřebuje aspoň podklad vždy.

Řešení pro start klidně minimální: jeden celorepublikový vector PMTiles
(~1–2 GB na z14, nebo rastrový fallback pro vybrané oblasti přes A2). Alternativa
pro online: vlastní klíč u bezplatné tier služby (MapTiler/Stadia) — rozhodnout
v rámci tohoto úkolu.

**Odhad:** L. **Závislosti:** žádné tvrdé; A2 pomůže.

---

## Blok B — Průvodci: „stáhnu a hned vím, jak na to"

### B1 — Uvítací průvodce při prvním spuštění  *(nové)*

**Popis.** Aplikace dnes nemá žádný onboarding (ověřeno — v kódu není first-run
stav). Nový uživatel uvidí prázdnou OSM mapu a neví, že jádro appky jsou
historické vrstvy. Přidat 4–5 stránek horizontálního pageru při prvním startu:

1. **Co to je** — „Historické mapy pro hledání s detektorem. Müller 1720,
   vojenská mapování, císařské otisky — přímo v terénu nad tvojí polohou."
2. **Vrstvy** — screenshot panelu, „tlačítkem 🗂 zapneš starou mapu přes dnešní;
   posuvníkem měníš průhlednost; podržením tlačítka nahlédneš pod vrstvu".
3. **Kalibrace** — „Staré mapy bývají posunuté. Vrstvy → Sladit: táhni starou
   mapu prstem, až pond sedne na pond. Uloží se to pro tuhle oblast."
4. **Offline** — „Co si na mapě prohlédneš, zůstane v telefonu i bez signálu.
   Celou oblast si můžeš stáhnout dopředu v Nastavení." *(stránka podmíněná A1/A2)*
5. **Pravidla a souhlas** — zkrácená polní pravidla (už existují v AboutScreen:
   ohlašovací povinnost nálezů, zákaz ÚAN/kulturních památek…) + „Pokračováním
   potvrzuješ, že jsi četl podmínky použití" → odkaz na C3. **Bez odsouhlasení
   nejde dál** — to je právní požadavek, ne UX ozdoba.

**Technika:** `HorizontalPager` (Compose Foundation, bez nové závislosti),
`booleanPreferencesKey("onboarding_done")` v existujícím DataStore,
route `Routes.ONBOARDING`, `AppNavHost` startDestination podmíněně. Obrázky:
skutečné screenshoty z aplikace (jsou k dispozici z ověřování v0.4.0), ne ilustrace.

**Acceptance criteria**
- [ ] Zobrazí se právě jednou; „Přeskočit" možné od stránky 1, souhlas na
      poslední stránce přeskočit nejde.
- [ ] Znovu vyvolatelný z Nastavení („Zobrazit úvod znovu").
- [ ] Compose test: průchod všemi stránkami, stav se persistuje.
- [ ] Texty česky, stručné — max 3 věty na stránku.

**Odhad:** M. **Závislosti:** C3 (texty souhlasu); stránka 4 podmíněně A1.

### B2 — Ilustrovaný návod ke kalibraci  *(nové)*

**Popis.** Sladit je nejmocnější a nejméně samopopisná funkce. Přidat:

1. **Jednorázový overlay při prvním vstupu do Režimu A**: poloprůhledná vrstva
   přes mapu s animovanou rukou („táhni jedním prstem = posun, dvěma = otočení
   a zvětšení") + „Rozumím". Klíčová rada, kterou nikdo neuhodne: **„Najdi
   objekt, který existuje na obou mapách — kostel, křižovatku, hráz rybníka —
   a přetáhni starý na dnešní."**
2. **Trvalá nápověda „?"** v `CalibrationBar` a v GCP editoru → bottom sheet
   s postupem krok za krokem, kdy použít Sladit (rychle, od oka) vs. GCP editor
   (přesně, po bodech) vs. Přiložit sken (I. VM, vlastní skeny).
3. Do GCP editoru doplnit radu, která plyne z geometrie: **druhý bod dávej
   co nejdál od prvního** — dva body u sebe dělají nestabilní otočení.

**Acceptance criteria**
- [ ] Overlay se ukáže jen poprvé (DataStore klíč), „?" je dostupné vždy.
- [ ] Nápověda vysvětluje všechny tři režimy a kdy který použít.
- [ ] Texty zkontrolované člověkem bez IT znalostí — srozumitelnost > úplnost.

**Odhad:** S–M. **Závislosti:** žádné.

### B3 — Výchozí stav pro nováčka  *(nové)*

**Popis.** Dnes je default jen OSM (ověřeno v `DefaultLayers.kt`:
`enabledByDefault = true` má jediná vrstva). Nováček neuvidí nic historického.

- Zapnout defaultně **II. VM online** (celá ČR, chartae) s opacity ~0.6 — hned
  první pohled ukáže, co appka umí. (Rozvaha: III. VM topo je detailnější, ale
  II. VM je vizuálně nejvýraznější „aha" moment.)
- Prázdné stavy: záložka Nálezy bez nálezů → „Zatím žádný nález. Na mapě ho
  přidáš žlutým +." (dnes prázdný seznam); totéž Místa.
- Po prvním fixu GPS: pokud je uživatel mimo pokrytí zapnuté offline vrstvy,
  jednorázový snackbar s vysvětlením („II. VM offline pokrývá jen Úpicko —
  online verze jede všude").

**Acceptance criteria**
- [ ] Čerstvá instalace ukáže historickou vrstvu bez jediného kliknutí.
- [ ] Merge katalogu (`mergeCatalogs`) nezmění nastavení stávajících uživatelů —
      default se aplikuje jen bez uloženého klíče `vis_*` (dnešní chování, otestovat).
- [ ] Každý prázdný seznam v aplikaci říká, jak vzniká první položka.

**Odhad:** S. **Závislosti:** žádné.

### B4 — Nápověda k detektorářské části  *(nové, malé)*

Rádce podmínek, presety, pre-flight a „Tvůj lov" jsou samopopisné jen napůl.
Jedna stránka „Jak číst Rádce" (co znamená vlhkost půdy, odkud jsou data, proč
je to odhad z 11km mřížky) linkovaná z Rádce i z pre-flightu. Texty už z velké
části existují v poznámkách obrazovek — jde o jejich zviditelnění.

**Odhad:** S. **Závislosti:** žádné.

---

## Blok C — Právní informace  *(paralelně, dlouhé čekací doby)*

### C1 — Písemné ověření podmínek poskytovatelů  *(= #34, konkretizace)*

**Popis.** Rozeslat dotazy a **archivovat odpovědi** do `docs/legal/` (datum,
kdo odpověděl, plné znění). Bez písemné odpovědi se vrstva ve veřejné verzi
chová podle nejpřísnějšího výkladu.

| Koho | Co ověřit | Pozn. |
|---|---|---|
| ČÚZK | ZTM, ortofoto, DMR 5G, archiv ÚAZK přes veřejnou aplikaci třetí strany; rate limit; atribuce | Od 2023 řada produktů ČÚZK open data (CC BY 4.0) — ověřit, které přesně a zda se vztahuje na prohlížecí služby |
| VÚGTK (chartae-antiquae.cz) | Müller, I.–III. VM: použití v aplikaci, cachování v telefonu, rate limit | Podmínky nejsou publikované vůbec — bez odpovědi vrstvy jen „live" bez A2 hromadného stahování |
| CENIA | II./III. VM S-JTSK služby | Pro veřejnou verzi aktuálně nepotřebné (nahrazeno chartae) — nízká priorita |
| Jihočeský, Moravskoslezský, Karlovarský kraj | Císařské otisky přes aplikaci | JčK výslovně zakazuje obchodní užití — appka je zdarma, ale ověřit výklad |
| NPÚ | ÚAN polygony ve veřejné aplikaci | Dnes jde o jednorázový export — pro veřejnou verzi zvážit odkaz na službu místo distribuce dat |
| OSM (OSMF) | Tile usage policy pro distribuovanou aplikaci | Viz A5 — pravděpodobně „ne bez svolení", řešením je vlastní podklad |

**Šablona dotazu** (napsat jednou, přizpůsobit): kdo jsem, nekomerční open source
aplikace pro amatérské hledače, odkaz na repo, co přesně od služby chci (live
prohlížení / cache v telefonu uživatele / hromadné stažení oblasti uživatelem),
odhad zátěže, nabídka rate limitu a identifikace User-Agentem.

**Acceptance criteria**
- [ ] Odesláno všem z tabulky, odpovědi (i „neodpověděli") zapsané v `docs/legal/`.
- [ ] `docs/DATA_SOURCES.md` doplněn o sloupec „veřejné šíření: ano / jen live / ne".
- [ ] Vrstvy bez souhlasu k hromadnému stahování jsou v A2 vyloučené (flag v katalogu).

**Odhad:** S práce + týdny čekání. **Začít hned.**

### C2 — Licence aplikace a repozitáře  *(nové)*

**Popis.** Zvolit licenci kódu a připravit repo na zveřejnění.

- **Doporučení: GPL-3.0** — zaručuje, že odvozeniny zůstanou otevřené (u aplikace
  pro komunitu žádoucí); alternativa MIT, pokud je priorita maximální šiřitelnost.
  Rozhodnutí je vlastníkovo — obě volby jsou obhajitelné.
- `LICENSE` do kořene; hlavičky do souborů nejsou nutné (jeden autor, jeden repo).
- **Audit repa před zveřejněním**: projít git historii na tajemství (klíče, hesla,
  tokeny) — `credentials.txt` je mimo repo (ověřeno), ale zkontrolovat celou
  historii (`git log -p | grep -i` na obvyklé vzory, případně gitleaks).
- README pro veřejnost: co to je, screenshoty, instalace APK, sekce „Odkud jsou
  mapy a čí jsou" s atribucemi, disclaimer (viz C4), návod na build.
- `data/` a podpisové klíče zůstávají mimo repo (`.gitignore` — ověřeno, drží).

**Acceptance criteria**
- [ ] LICENSE zvolena a v repu; README kompletní vč. atribucí.
- [ ] Historie čistá (gitleaks nebo ruční audit, výsledek zapsaný v handoff.md).
- [ ] V repu není žádný APK s vestavěnými mapovými daty.

**Odhad:** S. **Závislosti:** C1 částečně (atribuce do README).

### C3 — Právní texty v aplikaci  *(rozšíření AboutScreen)*

**Popis.** `AboutScreen` už má atribuce a čtyři polní pravidla — dobrý základ,
ale pro veřejnou verzi chybí:

1. **Podmínky použití / disclaimer** (nová sekce nebo samostatná obrazovka):
   - aplikace je pomůcka, ne právní rada; za soulad činnosti se zákonem odpovídá
     uživatel (zákon č. 20/1987 Sb. o státní památkové péči — hledání
     archeologických nálezů bez povolení je nelegální, nález je majetkem kraje,
     ohlašovací povinnost);
   - mapová data patří uvedeným institucím, aplikace je jen zobrazuje;
   - poloha GPS je orientační; autor neodpovídá za škody.
2. **Per-vrstva licence**: do `LayerDef` pole `license: String?` (např.
   „CC BY 4.0", „© ČÚZK — jen prohlížení") a zobrazit v panelu vrstev v detailu
   a v AboutScreen. Katalog verze +1, merge stávajícím uživatelům doplní.
3. **Soukromí**: výslovná věta „Aplikace neodesílá žádná data — nálezy, trasy
   i polohy zůstávají jen v telefonu" (je to pravda a je to prodejní argument;
   jediné síťové dotazy jsou dlaždice a počasí — vyjmenovat).
4. Verze, odkaz na repo, odkaz na hlášení chyb (GitHub issues).

**Acceptance criteria**
- [ ] Texty zreviduje člověk (ideálně s právním citem) před vydáním — v repu
      označit `TODO: right-check` dokud se nestane.
- [ ] Souhlas z B1 odkazuje přesně na tyto texty (jeden zdroj pravdy).
- [ ] Každá vrstva v katalogu má vyplněnou licenci nebo „neuvedeno — jen osobní užití".

**Odhad:** S–M. **Závislosti:** C1 (obsah), B1 (napojení souhlasu).

### C4 — Etický rámec  *(malé, ale viditelné)*

Polní pravidla z AboutScreen povýšit: sekce „Kodex hledače" viditelná
z onboardingu (B1 stránka 5) — ohlašovací povinnost, zákaz ÚAN a kulturních
památek (appka je sama zobrazuje — to je její etická výbava, zdůraznit),
souhlas vlastníka pozemku, zásada „zakopej po sobě". Jedna obrazovka, žádná nová
logika. **Odhad:** S.

---

## Blok D — Distribuce a robustnost

### D1 — Kanál distribuce  *(rozhodnutí + malá práce)*

**Popis.** Rozhodnout a nastavit:

- **Start: GitHub Releases** — APK podepsaný stávajícím klíčem, changelog
  z CHANGELOG.md, instalace „z neznámých zdrojů" popsaná v README s obrázky
  (cílová skupina to nezná!). Nejmenší práce, žádné review.
- **Střednědobě zvážit**: Obtainium/F-Droid (F-Droid vyžaduje reproducible build
  a žádné proprietární závislosti — splňujeme, ale je to práce navíc);
  Google Play (jednorázově 25 USD, review, ale jediný kanál, který cílovka
  opravdu umí — **doporučeno jako druhý krok po ustálení**).
- CI: release job — tag `v*` → build podepsaného APK (klíč v GitHub Secrets)
  → GitHub Release s artefaktem. Podpisové env proměnné už build podporuje.

**Acceptance criteria**
- [ ] `git tag v0.5.0 && git push --tags` vyrobí hotový release bez ručních kroků.
- [ ] README má sekci „Instalace pro nováčky" se screenshoty.

**Odhad:** S–M. **Závislosti:** C2.

### D2 — TLS 1.3 vs. minSdk  *(rozhodnutí)*

**Popis.** chartae-antiquae.cz (5 vrstev vč. jediných celorepublikových
vojenských mapování) je TLS 1.3-only. Android < 10 (API < 29) TLS 1.3 v
defaultu neumí → na Androidu 8/9 tyhle vrstvy tiše selžou.

Možnosti: (a) **zvednout minSdk 26 → 29** — nejčistší, ztratíme ~5–8 % zařízení
v ČR; (b) přibalit Conscrypt (~4 MB APK, funguje všude); (c) nechat 26 a u vrstev
hlásit „vyžaduje Android 10+". **Doporučení: (a)** — cílovka nosí do pole spíš
novější telefony a údržba dvou cest za 5 % nestojí. Rozhodnutí vlastníka.

**Acceptance criteria**
- [ ] Rozhodnuto a zapsáno v handoff.md; při (a) bump v build.gradle.kts,
      při (c) availability-check s čitelnou hláškou místo tichého selhání.

**Odhad:** S.

### D3 — Slušnost k serverům v ostrém provozu  *(rozšíření A3 na celou appku)*

- User-Agent s verzí a odkazem na repo u všech dlaždicových dotazů
  (`WmsTileRenderer`, `ArcGisTileArchive`, XYZ fetch, počasí).
- Globální strop souběžných dotazů na jeden hostname (např. 4) a exponenciální
  backoff při 429/503 — dnes retry logika řeší jen jednotlivý dotaz.
- Telemetrie žádná (viz C3 soukromí) — ale lokální čítač stažených dlaždic
  za den do Nastavení, ať uživatel vidí svou zátěž.

**Odhad:** S. **Závislosti:** žádné.

### D4 — Hlášení chyb bez telemetrie  *(nové)*

**Popis.** Veřejní uživatelé = pády, které neuvidíš. Bez porušení „nic
neodesíláme": zachytávat neošetřené výjimky (`Thread.setDefaultUncaughtExceptionHandler`),
zapsat do `files/crash/` a **při dalším startu nabídnout** „Aplikace minule
spadla — odeslat hlášení e-mailem?" → `ACTION_SEND` s logem jako přílohou.
Uživatel vidí přesně, co odchází, a odesílá sám.

**Acceptance criteria**
- [ ] Pád vygeneruje soubor s verzí, zařízením, stacktrace — bez polohy a nálezů.
- [ ] Nabídka odeslání je jednorázová na pád, odmítnutí soubor smaže.

**Odhad:** S. **Závislosti:** žádné.

---

## Souhrn: pořadí a milníky

| Milník | Obsah | Výsledek |
|---|---|---|
| **M1 „Offline jádro"** | A1 → A4, D3 | co jsem viděl, mám navždy; slušný klient |
| **M2 „Srozumitelnost"** | B1–B4, C3, C4 | nováček projde bez pomoci; souhlas s podmínkami |
| **M3 „Právně čisté"** | C1, C2, D2 | odpovědi institucí, licence, README, minSdk |
| **M4 „Vydání 1.0"** | D1, A2, A3 | GitHub Release, stahování oblastí |
| **M5 „Nezávislost"** | A5 (+ #33 katalog) | vlastní podklad, katalog map |

C1 (dopisy) odeslat **hned na začátku M1** — čekání běží paralelně s vývojem.
Fáze 2 každého milníku: ostré ověření na telefonu (vzor v handoff.md).

Známá rizika: VÚGTK neodpoví → chartae vrstvy jen „live" bez hromadného stahování
(cache z prohlížení zůstává obhajitelná); OSM policy → A5 povinné před 1.0;
Play Store review může chtít privacy policy URL → C3 text vystavit i jako
GitHub Pages stránku.
