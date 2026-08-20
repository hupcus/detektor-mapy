# Terénní příručka — DetektorMapy

> Praktický manuál k aplikaci: co udělat před výjezdem, jak ji ovládat v terénu
> a co dělat s nálezem. Issue **F5-3**.
>
> Doplňkové čtení: `docs/DATA_SOURCES.md` (odkud jsou mapy),
> `docs/ARCHITECTURE.md` (jak to uvnitř funguje).

---

## 1. Příprava před výjezdem

Všechno, co potřebuje síť, se dělá **doma**. V terénu aplikace pracuje offline.

### 1.1 Připravit data (doma, na počítači)

1. Vyber si zájmovou oblast — okres nebo katastr, ne celou ČR.
   PMTiles pro celou republiku je zbytečně velký (PLAN.md sekce 10).
2. Podle `tools/README.md` stáhni dlaždice a postav PMTiles pro vrstvy, které
   v dané oblasti chceš: minimálně **II. vojenské mapování** a **DMR 5G reliéf**,
   pro kalibraci se hodí i **ortofoto**.
3. Zkopíruj `.pmtiles` soubory do telefonu (USB nebo Syncthing) do
   `Android/data/cz.hh.detektormapy/files/layers/`.
4. Do stejné složky patří `layers.json` — jeden řádek na vrstvu.
   Přidání mapy = soubor + řádek, **žádná aktualizace aplikace**.
5. DMR 5G stahuj **jen pro konkrétní lokality**, ne pro celý okres — je datově těžký.

### 1.2 Nabít a zkontrolovat

- **Telefon nabitý na 100 %**, powerbanka s sebou. Záznam pochůzky drží GPS živou;
  cíl je 2 h záznamu za max 15 % baterie, ale mráz to změní.
- **Vypnout optimalizaci baterie** pro DetektorMapy, jinak systém zabije záznam
  pochůzky při zamčeném displeji.
- Detektor: nabité baterie, cívka v pořádku, rýpadlo, pinpointer, sáčky na nálezy.

### 1.3 Pre-flight kontrola v aplikaci

Projdi tenhle seznam **ještě doma, na WiFi** — v lese už nic nespravíš:

- [ ] Zapnout **letecký režim** a otevřít mapu. Vidím podklad? Vidí se historická vrstva?
      Když ne, chybí PMTiles nebo je špatně řádek v `layers.json`.
- [ ] Posunout mapu po celé zájmové oblasti a projet zoomy 12–17. Dlaždice, které
      si tímhle stáhnu do cache, budou v terénu okamžitě.
- [ ] Zkontrolovat, že se **chytá GPS** (venku, ne v bytě) a že kompas ukazuje správně.
- [ ] Mít v aplikaci **plánované lokality** (waypointy) na místa, která chci projít.
      Plánuje se doma nad mapou, ne v poli.
- [ ] Zkontrolovat vrstvu **ÚAN** — vidím, kde nesmím?
- [ ] Zálohovat databázi (viz sekce 8), pokud jsem od poslední zálohy nasbíral nálezy.

---

## 2. Ovládání mapy a vrstev

### Základ

| Akce | Gesto |
|---|---|
| Posun mapy | tažení jedním prstem |
| Zoom | štípnutí dvěma prsty / dvojklep |
| Otočení mapy | otočení dvěma prsty |
| Naklonění | tažení dvěma prsty nahoru/dolů |
| Nové místo (waypoint) | **dlouhý stisk** na mapě |
| Nový nález | tlačítko **FAB** vpravo dole |

**Follow-mode** drží mapu na mé poloze a otáčí ji podle kompasu — v lese je to jediný
rozumný režim, protože „nahoře" je pak vždycky směr, kterým se dívám. Jakmile mapou
ručně pohnu, follow-mode se vypne; zapne se zpátky tlačítkem polohy.

### Panel vrstev

Panel má pro každou vrstvu **checkbox** (viditelnost) a **slider průhlednosti**.
Nastavení přežije restart aplikace.

Praktické použití:

- Historická mapa na **60–75 %** průhlednosti nad OSM — vidím zároveň starý stav
  i dnešní cesty a lesy.
- Při hledání konkrétního objektu přepínej vrstvu rychle sem a tam. Oko chytí posun
  a rozdíl mnohem líp než při statickém pohledu.
- **Vrstvy s ikonou cloudu jsou online.** Bez signálu prostě zmizí — není to chyba,
  aplikace na to neupozorňuje a nespadne.
- Pořadí vrstev se řídí polem `order` v `layers.json`. Reliéf dávej **pod** historickou
  mapu, ne nad ni.

---

## 3. Rychlé sladění posunuté mapy (Režim A, do 30 s)

Historické mapy jsou georeferencované globálně, takže lokálně bývají posunuté
i o desítky metrů. Sladit je jde přímo v terénu.

**Postup:**

1. Najdi na obrazovce **záchytný bod, který existuje na obou mapách** a nepohnul se
   za 170 let: rybniční hráz, kostel, křižovatka polních cest, kamenný most,
   ostrý roh lesa, boží muka.
2. Zapni **kalibrační mód** (tlačítko v panelu vrstev).
3. **Dvěma prsty hýbej overlay vrstvou** — posun, otočení, změna měřítka.
   Podkladová OSM přitom stojí na místě.
4. Sesaď záchytný bod na sebe. Nesnaž se o dokonalost přes celou obrazovku — sesaď to,
   co je **kolem tebe**, ne to, co je na okraji.
5. Klepni na **„uložit pro tuto oblast"**.

Kalibrace se uloží pro bbox aktuálního výřezu. Až se do oblasti vrátíš, **aplikuje se
sama** a nahoře svítí indikátor aktivní kalibrace.

**Proč per-oblast:** jedna mapa může být v každém katastru posunutá jinak. Proto se
neukládá jedna korekce na vrstvu, ale korekce na region. V dalším údolí si uděláš novou.

**Kdy to nedělat:** Režim A je na **jemné doladění**, ne na posun o kilometry. Když mapa
sedí o půl kilometru vedle, je špatně samotný podklad — to se řeší doma v Režimu B
(GCP editor) a přesným warpem v `tools/warp_scan.py`.

---

## 4. Jak zaznamenat nález

Celý flow má trvat **do 15 sekund** — tolik, kolik trvá vyfotit a zaklapnout.

1. **FAB „nález"** → otevře se kamera.
2. **Vyfoť** — ideálně předmět *in situ*, v jamce, s něčím pro měřítko
   (mince, rýpadlo). GPS pozice se bere v okamžiku fotky.
3. **Formulář:** kategorie (mince / knoflík / vojenské / …), hloubka v cm, poznámka.
   Nic z toho není povinné, doplnit se dá večer.
4. **Ulož.** Na mapě vznikne pin.

Nález si pamatuje, **na jaké historické vrstvě jsi ho udělal** — v detailu pak vidíš,
že „tohle bylo na II. VM u zaniklého mlýna". To je po sezóně zlato.

**Praktické rady:**

- Fotky dělej **dvě**: jednu v jamce, jednu očištěného předmětu doma. K jednomu nálezu
  jich jde přiřadit až tři.
- Hloubku odhaduj poctivě. Za rok ti to řekne, jestli má smysl na lokalitu jít znovu
  s větší cívkou.
- Do poznámky si piš i **negativní informaci** („pole zorané, signály jen železo") —
  ušetří ti to příští výjezd.
- **Hvězdička** u nálezu je na to, co se má dostat do přehledu. Používej ji střídmě.

### Prohledané zóny

Polygon prohledané zóny nakreslíš prstem a označíš jako *hotovo* nebo *rozpracováno*.
Za dvě sezóny je to jediná věc, která ti řekne, kde jsi opravdu byl — paměť lže.

---

## 5. Jak číst LiDAR reliéf (DMR 5G)

Vrstva stínovaného reliéfu ukazuje **tvar terénu pod vegetací**. V lese vidí to,
co z letadla ani ze země vidět není. Na hillshade hledáš **pravidelnost** — příroda
dělá zřídka rovné čáry a přesné kruhy.

Nastav si reliéf **pod** historickou mapu a přepínej mezi nimi. Když se lineární útvar
na hillshade kryje s cestou na mapě z roku 1840, máš jistotu.

### Co hledat

**Úvozy (staré cesty)**
Souběžné rýhy zaříznuté do svahu, často několik vedle sebe — jak se cesta rozjezdila,
vznikala nová stopa. Na hillshade to vypadá jako paralelní tmavo-světlé pásy táhnoucí
se svahem, typicky mezi vsí a brodem, mlýnem nebo sedlem. Nejlíp je vidět na
zalesněných svazích, protože v poli je orba srovnala.
**Kde hledat:** okraje úvozu a místa, kde se úvoz stáčí nebo kříží potok.

**Milíře (uhliště)**
Ploché kruhové terasy o průměru zhruba 8–15 m, zaříznuté do svahu — z jedné strany
zásek, z druhé násyp. Na hillshade jsou to nápadně **kulaté rovné plošinky**, často
v hloučcích po několika v jednom lesním úseku. Bývají ve svahu nad potokem.
Půda na nich je černá od uhlí.
**Pozor:** samotný milíř je uhliště, ne sídliště — nálezy tam bývají chudé, ale
prozrazují pohyb lidí a bývá poblíž cesta.

**Valy a příkopy**
Dvojice paralelních útvarů: vyvýšený pás + přilehlá rýha. U hradišť a tvrzí obepínají
plochu v oblouku nebo kruhu. **Val bez příkopu bývá mez nebo hráz, ne opevnění.**
Ostrožny nad soutokem dvou potoků s valem přes šíji jsou klasika.
**Právní varování:** hradiště a tvrze jsou skoro vždy v ÚAN — viz sekce 7.

**Zaniklé cesty a plužiny**
Dlouhé mělké lineární útvary, které nikam „dnešního" nevedou. Plužiny zaniklých vsí
se projeví jako pravidelné souběžné pásy (terasy) na svahu — a v jejich těžišti bývá
zaniklá ves.

**Zaniklé usedlosti**
Obdélné plošinky a nízké haldy v pravidelném uspořádání, často podél zaniklé cesty.
Porovnej s II. VM: co tam v roce 1840 stálo a dnes je les, je kandidát.

### Metoda

1. Na reliéfu najdi **pravidelný útvar**.
2. Přepni na II. VM — je tam něco?
3. Přepni na ortofoto — je to dnes jen les/pole?
4. Zkontroluj **ÚAN** (sekce 7).
5. Udělej waypoint typu „plán" a jdi se podívat.

> Výchozí ČÚZK hillshade je jednosměrný a některé útvary v něm zaniknou. Vlastní
> multi-directional hillshade + sky-view factor (`tools/dmr5g_hillshade.py`, Fáze 5)
> je výrazně čitelnější.

---

## 6. Práce s vrstvou ÚAN

**ÚAN = území s archeologickými nálezy**, eviduje Národní památkový ústav.
V aplikaci se zobrazuje šrafovaně, klepnutí ukáže kategorii.

Kategorie zjednodušeně:

- **ÚAN I** — území s prokázaným výskytem archeologických nálezů.
- **ÚAN II** — území, kde je výskyt archeologických nálezů odůvodněně očekáván.
- **ÚAN III** — území, kde nebyl výskyt prokázán, ale nelze ho vyloučit.
- **ÚAN IV** — území, kde je výskyt vyloučen (vytěžené plochy apod.).

**Prakticky:** v ÚAN I a II **nehledej**. Nejde jen o pokutu — je to místo, kde
neodborné vykopání předmětu zničí informaci, která se už nedá získat zpátky.
Když v takovém území něco najdeš náhodou, platí postup ze sekce 7.

Volitelné varování při vstupu do ÚAN I/II si zapni v nastavení. Vyplatí se: hranice
polygonu v terénu nevidíš.

> ⚠️ Endpoint služby ÚAN je v `tools/sources.py` vedený jako **neověřený**
> (`verified=False`) — PLAN.md ho označuje jako „ověřit při implementaci".
> **Vrstvu proto neber jako právně závaznou.** Autoritativní jsou data NPÚ,
> ne to, co ti ukáže tahle aplikace.

---

## 7. Právní část — praktická pravidla

> **Toto není právní poradenství.** Je to praktický souhrn povinností, které se
> detektoráře v ČR týkají nejčastěji. Zákony a jejich výklad se mění a konkrétní
> situace může být jiná. Když si nejsi jistý, zeptej se přímo regionálního muzea
> nebo územního odborného pracoviště NPÚ.

### Pravidlo 1 — Archeologický nález oznam do 2 dnů

Když najdeš něco, co vypadá jako **archeologický nález** (nikoli ztracený mobil —
předmět historické hodnoty: mince, spony, součásti výstroje, depot, keramika),
máš povinnost ho **oznámit do 2 dnů** muzeu nebo NPÚ.

Prakticky: vyfoť to *in situ* ještě než to vytáhneš, zapiš přesnou GPS (aplikace to
dělá sama) a hned volej nebo piš na nejbližší muzeum s archeologickým pracovištěm.
Aplikace ti drží datum, souřadnice i fotky — máš čím oznámení doložit.

### Pravidlo 2 — S místem nálezu 5 dní nemanipuluj

Po nálezu **neprohrabávej okolí a nezvětšuj jamku**. Místo nálezu se nesmí měnit
**po dobu 5 dní** od oznámení, aby ho mohl archeolog posoudit v původním stavu.
To platí zvlášť u depotu — vyházet zbytek pokladu je nejhorší, co můžeš udělat.

### Pravidlo 3 — Bez souhlasu vlastníka nikam

Na cizí pozemek se souhlasem vlastníka nebo hospodáře. U polí to bývá zemědělské
družstvo nebo firma, která na tom hospodaří, ne majitel v katastru. U lesů majitel
lesa (obec, Lesy ČR, soukromník).

Prakticky: řekni dopředu, co děláš, a nabídni, že po sobě uklidíš. Odmítnutí respektuj.
Jeden konflikt zavře lokalitu všem na roky.

### Pravidlo 4 — ÚAN I a II bez povolení ne

V územích s archeologickými nálezy kategorie I a II se **bez příslušného povolení
nehledá**. Vrstva ÚAN v aplikaci je právě na to, abys to viděl dřív, než tam vlezeš.

Stejně tak: **národní kulturní památky, archeologické rezervace a chráněná území**
(NP, CHKO, přírodní rezervace) mají vlastní režim a detektor tam většinou nepatří.

### Pravidlo 5 — Nechovej se jako zloděj

Zahrň jamku, vrať drn, odnes si železný odpad. Nálezy nekopej v noci a neschovávej se.
Kdo se chová transparentně, s tím archeologové spolupracují.

### Když se to sejde

Praktická posloupnost po zajímavém nálezu:

1. **Nekopat dál.** Fotka *in situ*.
2. Záznam v aplikaci (GPS + foto + poznámka).
3. Zahrnout, označit místo waypointem.
4. **Do 2 dnů** oznámit muzeu / NPÚ.
5. **5 dní** s místem nemanipulovat.
6. Předat nález podle domluvy s muzeem.

---

## 8. Zálohování

Deník nálezů je nenahraditelný — telefon nahraditelný je.

**Export:** Nastavení → Export. Vznikne **zip** s:

- GeoJSON nálezů a míst,
- GPX (tracky, waypointy) — otevřeš v QGIS nebo Locusu,
- fotkami.

Zip se uloží do **Downloads**. Odtud si ho odsyncuj sám (Syncthing, cloud, USB) —
aplikace nikam sama nic neposílá.

**Import** vezme stejný zip zpátky. Roundtrip export → smazání → import musí projít
beze ztrát.

**Rytmus:** export po každém výjezdu, který něco přinesl. Trvá to pár sekund.

**Co zálohovat nemusíš:** PMTiles vrstvy. Ty si kdykoliv znovu postavíš z `tools/`
podle `tools/README.md` — to je celý smysl reprodukovatelné pipeline.

---

## 9. Troubleshooting

### Mapa se nezobrazuje

| Příznak | Příčina | Řešení |
|---|---|---|
| Prázdná šedá plocha, žádná vrstva | chybí PMTiles soubory | zkontroluj `Android/data/cz.hh.detektormapy/files/layers/` |
| Vrstva je v panelu, ale škrtnutá / „nedostupná" | `layers.json` odkazuje na soubor, který na disku není | oprav jméno souboru v `layers.json` (přesně, včetně přípony) |
| Vrstva zmizela venku, doma fungovala | je to **online** vrstva (ikona cloudu) | stáhni si ji do PMTiles, nebo se smiř s tím, že bez signálu není |
| Podklad OSM je prázdný | offline basemapa není nainstalovaná | zapni WiFi doma a projeď oblast, nebo nainstaluj vector PMTiles ČR |
| Mapa je posunutá o desítky metrů | globální georeference historické mapy | Režim A, sekce 3 |
| Mapa je posunutá o kilometry | špatný podklad, ne kalibrace | řeš doma přes GCP editor a `tools/warp_scan.py` |
| Overlay je vidět, ale strašně bledý | opacity na nule | slider v panelu vrstev |

Když nepomůže nic: zavři a znovu otevři aplikaci. Dlaždicový server běží lokálně
a restartem se čistě nahodí.

### GPS nechytá

1. **Jsi venku?** Pod střechou, v autě a v hustém jehličnatém lese to trvá.
2. **Dej telefonu 1–2 minuty** na první fix. Studený start po dlouhé cestě
   (nebo po leteckém režimu) je pomalý.
3. **Zkontroluj oprávnění polohy** — aplikace potřebuje „přesnou polohu".
   V nastavení Androidu, ne v aplikaci.
4. **Vypni úsporný režim.** Systém omezuje GPS agresivně a bez varování.
5. **Letecký režim vypni**, i když jedeš offline — vypíná v některých telefonech
   i asistovanou lokalizaci a fix pak trvá násobně déle.
6. Když poloha „skáče" o desítky metrů, počkej. Přesnost se srovná, jak přibývají
   družice. Nález zaznamenaný při špatné přesnosti bude mít špatné souřadnice —
   raději počkej pár sekund.

### Baterie

| Problém | Řešení |
|---|---|
| Rychle klesá při záznamu | sniž jas, vypni displej — track běží dál na pozadí |
| Záznam se sám ukončil | vypni **optimalizaci baterie** pro DetektorMapy v nastavení Androidu |
| Telefon se vypnul v mrazu | v zimě nos telefon v kapse blíž tělu; studená baterie ztrácí kapacitu |
| Rychle klesá bez záznamu | zkontroluj, jestli nemáš zapnutou online vrstvu, která pořád stahuje |

Obecně: **žádná online vrstva + vypnutý displej + offline PMTiles = nejnižší spotřeba.**
To je taky přesně to, na co je aplikace stavěná.

---

## 10. Rychlý checklist do kapsy

**Před výjezdem**
- [ ] PMTiles v telefonu, ověřeno v leteckém režimu
- [ ] Telefon 100 % + powerbanka
- [ ] Optimalizace baterie vypnutá
- [ ] Waypointy naplánované
- [ ] ÚAN zkontrolované

**V terénu**
- [ ] Follow-mode zapnutý
- [ ] Historická vrstva na ~70 % + reliéf pod ní
- [ ] Mapa nesedí → Režim A, 30 s
- [ ] Nález → foto *in situ* → formulář → uložit
- [ ] Prohledanou zónu zakreslit

**Po nálezu, který vypadá archeologicky**
- [ ] Nekopat dál, fotka *in situ*
- [ ] Do 2 dnů oznámit muzeu / NPÚ
- [ ] 5 dní s místem nemanipulovat

**Doma**
- [ ] Export zip do Downloads
- [ ] Doplnit poznámky a fotky očištěných nálezů
