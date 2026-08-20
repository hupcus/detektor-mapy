# Nokta Legend – doporučené presety pro ČR / Podkrkonoší

**Určení:** les, louka a pole – vždy ve variantě **běžné podmínky** a **mokro / vodivá půda**  
**Detektor:** Nokta The Legend  
**Firmware:** V1.17  
**Výchozí cívka:** LG30 / standardní střední cívka  

> Toto nejsou tovární presety Nokty. Jsou to praktická doporučená nastavení složená z oficiální dokumentace Nokta V1.17 a běžných zkušeností uživatelů. Citlivost, Ground Balance a někdy i Recovery Speed je vždy potřeba doladit na konkrétním místě.

---

## Rychlý přehled všech 6 presetů

| Nastavení | LES | LES – MOKRO | LOUKA | LOUKA – MOKRO | POLE | POLE – MOKRO |
|---|---:|---:|---:|---:|---:|---:|
| Režim | FIELD | FIELD | FIELD | FIELD | FIELD | FIELD |
| Multi frekvence | **M1** | **M3** | **M1** | **M3** | **M2** | **M3** |
| Diskriminace | **A – All Metal** | **A – All Metal** | **A – All Metal** | **A – All Metal** | **A – All Metal** | **A – All Metal** |
| Citlivost – start | **25** | **24** | **26** | **24–25** | **25** | **24** |
| Recovery Speed | **4** | **5** | **4** | **5** | **5** | **5** |
| Iron Filter – IF | **2** | **2** | **2** | **2** | **2** | **2** |
| Stability – St | **2** | **3** | **2** | **3** | **2** | **3** |
| Bottle Cap – bC | **0** | **0** | **0** | **0** | **0** | **0** |
| Ground Suppressor – GS | **0** | **0** | **0** | **0** | **0** | **0** |
| Deep Target ID – dt | **1** | **0** | **1** | **0** | **1** | **0** |
| Audio Gain – AG | **4** | **3** | **4** | **3** | **3** | **3** |
| Počet tónů | **60** | **60** | **60** | **60** | **60** | **60** |
| Hlasitost železa Z1 | **2** | **2** | **2** | **2** | **2** | **2** |
| Hlasitost barvy Z2 | **10** | **10** | **10** | **10** | **10** | **10** |
| Frekvence tónu Z1 | **2** | **2** | **2** | **2** | **2** | **2** |
| Frekvence tónu Z2 | **25** | **25** | **25** | **25** | **25** | **25** |
| Tone Break | **11** | **11** | **11** | **11** | **11** | **11** |
| Threshold | **0** | **0** | **0** | **0** | **0** | **0** |
| Ground Tracking | **OFF** | **OFF** | **OFF** | **OFF** | **OFF** | **OFF** |

---

# PROFIL 1 – LES

Použití: běžný český les, lesní cesty, staré komunikace, okolí zaniklých cest a běžná podkrkonošská půda, pokud není výrazně mokrá nebo nestabilní.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M1
- **Diskriminace:** A – All Metal
- **Citlivost:** 25
- **Recovery Speed:** 4
- **Iron Filter IF:** 2
- **Stability St:** 2
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 1
- **Audio Gain AG:** 4
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

M1 je vhodný jako základ pro běžnou půdu a dává velmi dobré výsledky na střední a vyšší vodiče, tedy typické mince a relikvie. Recovery 4 upřednostňuje hloubku před extrémní separací. IF 2 nechává detektor poměrně otevřený, ale St 2 ho stále drží rozumně stabilní.

## Doladění v terénu

- **Je moc citlivý / náhodně pípá:** nejdřív Noise Cancel + Ground Balance, potom Sens **24**, případně **23**.
- **Je klidný a chceš víc dosahu:** zkus Sens **26**. Pokud přibydou falešné signály, vrať 25.
- **Hodně železa:** Recovery **5–6**. Při silném maskování lze zkusit IF **1**.
- **Rezavé železo moc prozvukuje do barvy:** St **3**, případně IF zpět na **2–3**.
- **Slabé hluboké cíle špatně slyšíš:** nech Recovery 4 a můžeš zkusit AG **5**; počítej s větší „ukecaností“.
- **Půda je mokrá nebo reaguje na cívku:** přepni na **LES – MOKRO / M3**.
- **Zem stále falešně reaguje i po GB:** GS **1**; GS **2** až když jednička nestačí.

---

# PROFIL 2 – LES – MOKRO

Použití: les po dešti, mokré podloží, vlhké údolnice, vodivější půda, mokrý jehličnatý les nebo situace, kdy M1 začne dávat mnoho falešných zemních signálů.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M3
- **Diskriminace:** A – All Metal
- **Citlivost:** 24
- **Recovery Speed:** 5
- **Iron Filter IF:** 2
- **Stability St:** 3
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 0
- **Audio Gain AG:** 3
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

M3 Nokta navrhla právě pro vlhké, mokré a vodivé půdy. Oproti M1 je zde cílem hlavně stabilita. Proto je Recovery o stupeň výš, St 3 a dt vypnuté.

## Doladění v terénu

- **Pořád je hlučný:** Sens **23**, při opravdu problematické půdě **22**.
- **Zem pípá při dojezdu cívky k povrchu:** zopakuj Ground Balance; potom GS **1**.
- **GS 1 nestačí:** GS **2**. Výš už jen výjimečně.
- **M3 je až příliš klidná a půda už oschla:** vrať se na **M1**.
- **Chceš víc hloubky na čistém místě:** Recovery **4**, ale jen když stroj zůstane stabilní.
- **Hodně železa:** Recovery **6**, IF **1–2** podle množství falešných barevných tónů.
- **Slabé cíle jsou moc potichu:** AG **4**.

---

# PROFIL 3 – LOUKA

Použití: louky, pastviny a travnaté plochy mimo moderní parky. Vhodné hlavně tam, kde očekáváš starší mince a předměty uložené relativně hluboko v dlouhodobě nenarušené půdě.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M1
- **Diskriminace:** A – All Metal
- **Citlivost:** 26
- **Recovery Speed:** 4
- **Iron Filter IF:** 2
- **Stability St:** 2
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 1
- **Audio Gain AG:** 4
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

Na relativně čisté louce můžeš využít nižší Recovery a o něco vyšší citlivost. Cílem je dobrý dosah na hlubší mince a slabší signály. AG 4 slabé odezvy více vytáhne do zvuku, ale nezvyšuje fyzickou hloubku detektoru.

## Doladění v terénu

- **Moc citlivý / falešné pípání:** Sens **25**, případně **24**.
- **Klidná louka:** zkus Sens **27**. Pokud je zvuk horší než na 26, vrať se.
- **Louka je plná železa:** Recovery **5–6**.
- **Je hodně moderního odpadu:** zkus **M2** a Recovery **5**.
- **Chceš maximum hloubky na čisté louce:** Recovery **3–4**, pomalejší vedení cívky, Sens jen tak vysoko, aby stroj zůstal klidný.
- **Hluboké slabé signály jsou málo slyšet:** AG **5**.
- **Po dešti nebo na mokré louce:** přejdi na **LOUKA – MOKRO**.

---

# PROFIL 4 – LOUKA – MOKRO

Použití: mokrá louka po dešti, podmáčená tráva, vlhká hlinitá půda, níže položená místa a situace, kdy M1 začne reagovat na zem nebo vlhkost.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M3
- **Diskriminace:** A – All Metal
- **Citlivost:** 24–25
- **Recovery Speed:** 5
- **Iron Filter IF:** 2
- **Stability St:** 3
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 0
- **Audio Gain AG:** 3
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

Mokrá louka umí být vodivější než stejná lokalita za sucha. M3 je vhodnější pro stabilitu na vlhké půdě, Recovery 5 pomůže omezit zemní pazvuky a dt 0 drží odezvu čitelnější.

## Doladění v terénu

- **Je pořád moc citlivý:** začni na Sens **24**, pak **23**.
- **Je překvapivě klidný:** můžeš zkusit Sens **25–26**.
- **Půda reaguje při každém zhoupnutí:** zopakuj Ground Balance, pak GS **1**.
- **Chceš větší dosah na čistém místě:** Recovery **4**.
- **Je hodně železa nebo drobného odpadu:** Recovery **6**.
- **M3 působí zbytečně utlumeně a místo vysychá:** vrať **M1**.
- **Slabé cíle chceš více vytáhnout do zvuku:** AG **4**.

---

# PROFIL 5 – POLE

Použití: oraná a obdělávaná pole, strniště a lokality s větším množstvím železa a drobných cílů. Profil je nastaven tak, aby dobře reagoval i na malé a nižší vodiče.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M2
- **Diskriminace:** A – All Metal
- **Citlivost:** 25
- **Recovery Speed:** 5
- **Iron Filter IF:** 2
- **Stability St:** 2
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 1
- **Audio Gain AG:** 3
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

M2 je dobrý univerzální základ pro pole, kde chceš zachovat citlivost na menší a nižší vodiče. Recovery 5 je kompromis mezi separací v železe a dosahem. AG 3 zbytečně nezvýrazňuje všechny slabé půdní a železné pazvuky.

## Doladění v terénu

- **Moc citlivý / skáče ID:** Sens **24**, případně **23**.
- **Čisté pole a cíle daleko od sebe:** Recovery **4**.
- **Hodně železa:** Recovery **6**, případně IF **1** pro agresivnější odmaskování barvy mezi železem.
- **IF 1 dává moc falešných barevných tónů:** vrať IF **2** nebo St **3**.
- **Chceš hlavně větší/střední mince a vyšší vodiče:** přepni **M2 → M1**.
- **Chceš lépe slyšet slabé cíle:** AG **4**.
- **Mokrá, vodivá půda:** přepni na **POLE – MOKRO / M3**.

---

# PROFIL 6 – POLE – MOKRO

Použití: mokré oranice, pole po dešti, těžší vodivá hlína, mazlavá půda a situace, kdy M2 začne dávat falešné zemní signály.

## Nastavení

- **Režim:** FIELD
- **Frekvence:** M3
- **Diskriminace:** A – All Metal
- **Citlivost:** 24
- **Recovery Speed:** 5
- **Iron Filter IF:** 2
- **Stability St:** 3
- **Bottle Cap bC:** 0
- **Ground Suppressor GS:** 0
- **Deep Target ID dt:** 0
- **Audio Gain AG:** 3
- **Počet tónů:** 60
- **Tone Volume Z1 – železo:** 2
- **Tone Volume Z2 – neželezo:** 10
- **Tone Frequency Z1:** 2
- **Tone Frequency Z2:** 25
- **Tone Break:** 11
- **Threshold:** 0
- **Ground Tracking:** vypnuto

## Proč

M3 dává na mokré a vodivější půdě přednost stabilitě před maximální agresivitou. Na mokré oranici je čitelný zvuk často cennější než o jeden až dva body vyšší citlivost.

## Doladění v terénu

- **Půda je pořád hlučná:** Sens **23**, pak GS **1**.
- **Velmi těžká mokrá zem:** Recovery **6** může zlepšit čitelnost, ale trochu ubere hloubku.
- **Pole je překvapivě čisté:** Recovery **4** a Sens **25**.
- **Hodně železa:** Recovery **6**, IF **1–2**.
- **M3 je příliš utlumená a půda není ve skutečnosti vodivá:** vrať se na **M2**.
- **Po vyschnutí:** standardně zpět **M2 / Sens 25 / Recovery 5**.
- **Hluboké slabé cíle chceš více slyšet:** AG **4**, pokud tím nevznikne příliš mnoho pazvuků.

---

# Startovací rutina na KAŽDÉ lokalitě

Preset není náhrada za nastavení podle konkrétní země. Po příchodu udělej vždy toto:

1. **Načti správný preset.**
2. **Noise Cancel / Frequency Shift.**
   - Zvedni cívku do vzduchu mimo kovové předměty.
   - Spusť automatický výběr nejtiššího kanálu.
3. **Ground Balance.**
   - Najdi čisté místo bez kovu.
   - Pumpuj cívkou přibližně od 15–20 cm do cca 3 cm nad zemí.
   - Cívku drž rovnoběžně se zemí.
4. **Ground Tracking nech standardně OFF.**
5. Ujdi přibližně 20–30 m a sleduj stabilitu.
6. Pokud je detektor hlučný, nejprve znovu Noise Cancel a Ground Balance.
7. Teprve potom sniž citlivost o 1–2 body.
8. Pokud za nestabilitu může mokrá/vodivá zem, přepni na **M3**.
9. **GS** použij až jako poslední krok; začni hodnotou **1**.

---

# Rychlé ladění podle problému

## 1. Detektor je moc citlivý / „ukecaný“

Postupuj v tomto pořadí:

**Noise Cancel → Ground Balance → Sens -1 → Sens -2 → vhodnější Multi (za mokra M3) → St +1 → GS 1**

Nesnižuj hned Recovery ani nezvyšuj IF bez rozmyslu. Nejprve zjisti, jestli problém není EMI nebo půda.

## 2. Detektor je až moc klidný a chceš větší hloubku

Postup:

**Sens +1 → Recovery -1 → AG +1**

Dělej vždy jen jednu změnu a pár metrů ji otestuj. Pokud přibyde šum, vrať poslední krok.

## 3. Hodně železa / cíle jsou blízko sebe

Postup:

**Recovery +1 → případně IF 1 → pomalejší a kratší přejezdy → kontrola cíle z 90°**

Malá cívka zde často pomůže víc než další zásahy do menu.

## 4. Rezavé železo dává falešnou barvu

Postup:

**St +1 → IF +1 → kontrola z více směrů → FerroCheck u silnějších/mělčích cílů**

## 5. Mokrá nebo vodivá půda

Postup:

**M3 → Ground Balance → Sens 24 → Recovery 5 → dt 0 → St 3 → až potom GS 1**

## 6. Slabé hluboké cíle jsou špatně slyšet

Postup:

**Recovery o 1 níž → AG o 1 výš → pomalejší vedení cívky**

AG zesiluje zvuk slabých cílů, ale nezvyšuje fyzickou hloubku detektoru.

---

# Co znamenají důležitá pokročilá nastavení

## IF – Iron Filter

Nižší IF nechává detektor více otevřený a může lépe vytáhnout barevný cíl mezi železem. Současně ale roste riziko, že železo občas zazní jako barva.

**Pro tyto presety: IF 2.**

## St – Stability

Jemné doladění chování Iron Filteru. U běžného hledání používáme hodnotu 2. V problematičtějším prostředí 3.

> Neplést s původní plážovou Stability. Nokta rozlišuje plážovou stabilitu a St navázané na Iron Filter v Park/Field/Gold Field.

## GS – Ground Suppressor

Potlačuje falešné zemní signály v obtížném terénu. Nokta doporučuje nechat jej vypnutý, pokud není skutečně potřeba.

**Výchozí nastavení: GS 0.**

## dt – Deep Target Identification

Pomáhá hlubokým neželezným cílům, které už půda nebo hloubka tlačí do železné odezvy. Vyšší hodnoty mohou snížit stabilitu.

**Běžně: dt 1. Mokrá/problematická půda: dt 0.**

## AG – Audio Gain

Zesiluje hlasitost slabých cílových odezev. **Nezvyšuje fyzickou hloubku detektoru.** Vyšší AG ale usnadňuje slyšení hlubokých/slabých signálů a zároveň může subjektivně zvýšit „ukecanost“ detektoru.

## Recovery Speed

- nižší hodnota = větší důraz na hloubku,
- vyšší hodnota = lepší separace blízkých cílů a rychlejší odezva.

Pro tyto presety používáme nejčastěji:

- **4** na čistší les a louku,
- **5** na pole a mokré varianty,
- **6** v místech s velkým množstvím železa.

---

# Doporučené používání User Profiles

Legend má **4 User Profiles**, takže všech 6 presetů nemůžeš mít současně jako šest samostatných profilů. Prakticky bych to řešil takto:

- **User Profile 1 → LES**
- **User Profile 2 → LOUKA**
- **User Profile 3 → POLE**
- **User Profile 4 → aktuální MOKRO preset podle lokality**

Před výpravou si tedy Profil 4 přenastavíš jako **LES – MOKRO**, **LOUKA – MOKRO** nebo **POLE – MOKRO** podle toho, kam jdeš.

## Uložení profilu

1. Nastav celý detektor podle vybraného presetu.
2. Otevři **Settings**.
3. Najdi **User Profile**.
4. Stiskni **+**.
5. Pomocí **+ / -** vyber číslo profilu.
6. **Dlouze podrž Pinpoint / Accept-Reject** pro uložení.
7. U uloženého profilu se zobrazí potvrzení / fajfka.

**Pozor:** pokud používáš uložený profil jako aktivní, následné změny jeho nastavení se mohou do profilu ukládat. Když chceš experimentovat bez přepsání referenčního nastavení, nejdřív přepni na jiný profil.

---

# Moje doporučené pořadí při hledání v Podkrkonoší

### Běžný les
**FIELD → M1 → Sens 25 → Recovery 4 → IF2 / St2 → dt1 → GS0**

### Mokrý les
**FIELD → M3 → Sens 24 → Recovery 5 → IF2 / St3 → dt0 → GS0**

### Louka
**FIELD → M1 → Sens 26 → Recovery 4 → IF2 / St2 → dt1 → GS0**

### Mokrá louka
**FIELD → M3 → Sens 24–25 → Recovery 5 → IF2 / St3 → dt0 → GS0**

### Pole
**FIELD → M2 → Sens 25 → Recovery 5 → IF2 / St2 → dt1 → GS0**

### Mokré pole
**FIELD → M3 → Sens 24 → Recovery 5 → IF2 / St3 → dt0 → GS0**

---

# Zdroje a základ doporučení

- **Nokta The Legend User Manual – Software V1.17** – aktuální oficiální manuál.
- **Nokta The Legend Software Update** – změny V1.09 až V1.17, zejména IF/St, M3, Audio Gain, Ground Suppressor, Deep Target Identification a úpravy IF 0.
- **Český manuál The Legend v projektu** – základní obsluha, Ground Balance, Recovery Speed, Target ID, tóny a User Profiles.
- Nastavení hodnot jednotlivých presetů je praktické doporučení, nikoliv tovární nastavení Nokty. Je zvolené s důrazem na český les, louky a pole a na použití v Podkrkonoší.

---

## Jedna zásada, kterou bych držel vždy

**Nehonit maximální citlivost. Klidný Legend na 24–26 je v reálné půdě užitečnější než nestabilní detektor na 28–30.**
