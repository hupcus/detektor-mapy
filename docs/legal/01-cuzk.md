# ČÚZK — návrh dopisu

> **Neodesláno. Návrh k odsouhlasení.**

## Adresát (ověřeno 2026-08-21)

Český úřad zeměměřický a katastrální
Pod sídlištěm 1800/9, Kobylisy, 182 11 Praha 8
E-podatelna: `cuzk@cuzk.cz` · ID datové schránky: `uuaaatg` · tel. +420 284 041 111

## Čeho se týká

| Vrstva v aplikaci | Služba |
|---|---|
| `ztm` | `ZTM_WM` — dlaždicová cache Základní topografické mapy |
| `ortofoto` | `ORTOFOTO_WM` — dlaždicová cache Ortofota ČR |
| `dmr5g` | WMS stínovaného reliéfu DMR 5G (`ags.cuzk.gov.cz/arcgis2/services/dmr5g/…`) |
| desktop pipeline | archiv ÚAZK přes veřejnou aplikaci `ags.cuzk.gov.cz/archiv` |

## Co je u ČÚZK navíc potřeba doptat

ČÚZK od roku 2023 uvolnil část produktů jako otevřená data. **Nevíme, které přesně
a zda se to vztahuje i na prohlížecí služby** — to je hlavní věcná otázka dopisu; bez ní
bychom se ptali na něco, co už možná dávno smíme.

Druhá věc je **archiv ÚAZK**: skeny se stahují přes tokenem chráněné rozhraní veřejné
prohlížecí aplikace (`tools/archiv_fetch.py`). To je legitimně sporné a je poctivější
se na to zeptat, než čekat, jestli si toho někdo všimne.

---

## Text

**Předmět:** Dotaz na podmínky užití prohlížecích služeb ČÚZK v nekomerční aplikaci

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**, která amatérským hledačům s detektory kovů zobrazuje nad jejich
aktuální polohou historické a současné mapové podklady. Aplikace je a zůstane zdarma,
bez reklam, bez jakéhokoli zpeněžení a bez sběru dat o uživatelích; zdrojový kód je
veřejný na `github.com/hupcus/detektor-mapy`.

Aplikace záměrně **odrazuje od nelegálního hledání**: zobrazuje území s archeologickými
nálezy podle podkladů NPÚ a upozorňuje na ohlašovací povinnost i na to, že vyhledávání
archeologických nálezů bez oprávnění je podle zákona č. 20/1987 Sb. protiprávní.

Rád bych se zeptal na podmínky užití těchto vašich služeb:

- dlaždicová cache **Ortofoto ČR** (`ORTOFOTO_WM`),
- dlaždicová cache **Základní topografické mapy** (`ZTM_WM`),
- **WMS stínovaného reliéfu DMR 5G**.

Konkrétně:

1. **Živé zobrazení** dlaždic ve výřezu, který si uživatel právě prohlíží — je to
   pro takovou aplikaci v pořádku?
2. **Uložení již zobrazených dlaždic v telefonu uživatele**, aby mapa fungovala i bez
   signálu. Data zůstávají v jeho zařízení a nikam se dál nešíří; pro vaše servery to
   znamená, že tentýž uživatel tutéž dlaždici podruhé nestáhne.
3. **Stažení zvolené oblasti dopředu** na žádost uživatele (řádově tisíce až desítky
   tisíc dlaždic v jednom sezení). Tuhle funkci zapneme jen tam, kde to poskytovatel
   výslovně dovolí; pokud určíte strop nebo denní limit, nastavím se podle něj.

Dále bych se rád zeptal na dvě věci, které z veřejně dostupných informací neumím
spolehlivě vyčíst:

4. **Otevřená data.** Od roku 2023 je řada produktů ČÚZK poskytována jako otevřená data.
   Vztahuje se tento režim i na výše uvedené **prohlížecí služby**, nebo jen na datové
   sady ke stažení? A pokud ano, pod jakou licencí (CC BY 4.0?) a s jakým přesným zněním
   atribuce, kterou mám v aplikaci uvádět?
5. **Archiv ÚAZK.** Pro georeferencování jednotlivých listů stabilního katastru
   používám skeny z vaší veřejné aplikace `ags.cuzk.gov.cz/archiv` (stažení přes totéž
   rozhraní, které používá sama aplikace, po jednotlivých listech, bez plošného
   stahování). Je takové užití přípustné, nebo je pro to určená jiná cesta?

Co dodržujeme bez ohledu na odpověď: každý dotaz nese identifikaci
`DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`; nejvýše 4 souběžné dotazy na
jeden server; při odpovědi 429 nebo 503 se aplikace na exponenciálně rostoucí dobu
odmlčí, respektuje hlavičku `Retry-After` a dotaz neopakuje; atribuce „© ČÚZK" je
uvedena u vrstvy i v sekci „O aplikaci".

Pokud vám kterýkoli z bodů nevyhovuje, dejte prosím vědět — funkci vypnu, omezím,
nebo vrstvu z aplikace odstraním.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_
