# NPÚ — návrh dopisu

> **Neodesláno. Návrh k odsouhlasení.**

## Adresát (ověřeno 2026-08-21)

Národní památkový ústav, generální ředitelství
Valdštejnské náměstí 162/3, 118 01 Praha 1
E-podatelna: `epodatelna@npu.cz` · ID datové schránky: `2cy8h6t` · tel. +420 257 010 111
Sekretariát: `sekretariat@npu.cz`

## Čeho se týká

Vrstva `uan` — polygony území s archeologickými nálezy, dnes v aplikaci jako
**jednorázový export do GeoJSONu** z
`geoportal.npu.cz/arcgis/rest/services/Tematicke/CP_UAN/MapServer` (vrstvy 0–3).

## Zvláštnost tohohle dopisu

Tady se neptáme, jestli smíme zobrazovat cizí mapu. Ptáme se, jestli je jim milejší,
když data **distribuujeme** (soubor v aplikaci, funguje offline v lese, ale zastarává),
nebo když se aplikace **dotazuje jejich služby** (vždy aktuální, ale bez signálu nic).
Z hlediska památkové péče je offline varianta lepší — hledač v lese bez signálu je přesně
ten, kdo potřebuje vidět, že stojí na ÚAN. Ten argument je v dopise vyslovený nahlas.

---

## Text

**Předmět:** Dotaz na užití vrstvy ÚAN v nekomerční aplikaci pro hledače s detektory

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**. Zobrazuje amatérským hledačům s detektory kovů historické mapy
nad jejich aktuální polohou. Aplikace je a zůstane zdarma, bez reklam a bez sběru dat
o uživatelích; zdrojový kód je veřejný na `github.com/hupcus/detektor-mapy`.

Píšu kvůli vrstvě **území s archeologickými nálezy (ÚAN)** z vašeho geoportálu
(`Tematicke/CP_UAN/MapServer`), kterou v aplikaci zobrazuji.

Důvod, proč ji zobrazuji, je preventivní. Aplikace uživateli ukazuje, kde ÚAN je,
a upozorňuje, že vyhledávání archeologických nálezů bez oprávnění je podle zákona
č. 20/1987 Sb. protiprávní, že nález je majetkem kraje a že se musí ohlásit. Jsem
přesvědčen, že hledač, který vidí hranici ÚAN na displeji, se jí spíš vyhne než ten,
který o ní neví — a bez signálu v lese ji nevidí nikdo.

Z toho plyne moje otázka. Vrstvu jde do aplikace dostat dvěma způsoby a **každý má jinou
nevýhodu, tak bych rád věděl, který je vám milejší**:

1. **Jednorázový export polygonů do souboru v aplikaci.** Funguje i bez signálu — což je
   v terénu obvyklý stav a pro účel „vidět, že tu nemám co dělat" zásadní. Nevýhoda:
   data zastarávají, dokud nevydám novou verzi, a fakticky je tím šířím dál.
2. **Živý dotaz na vaši službu.** Data jsou vždy aktuální a nic se nešíří. Nevýhoda:
   bez signálu se nezobrazí nic, tedy právě tam, kde je varování nejvíc potřeba.

Konkrétně se tedy ptám:

- Je z vaší strany přijatelné, aby veřejně šířená nekomerční aplikace **obsahovala
  export polygonů ÚAN**? Pokud ano, s jakou atribucí a jak často mám data obnovovat?
- Pokud ne, je v pořádku **dotazovat se za běhu vaší služby**? Aplikace by se ptala
  jen na aktuální výřez, s identifikací `DetektorMapy/<verze>
  (github.com/hupcus/detektor-mapy)`, nejvýše 4 souběžnými dotazy a s odmlčením při
  odpovědi 429 nebo 503.
- Existuje pro ÚAN oficiální cesta k datům (otevřená data, licence), kterou jsem
  přehlédl a měl bych použít místo obojího?

Pokud vám nevyhovuje ani jedna varianta, dejte prosím vědět a vrstvu z aplikace
odstraním — byť by mě to mrzelo, protože je to jediná část aplikace, která uživatele
aktivně odrazuje od toho, aby dělal něco protiprávního.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_
