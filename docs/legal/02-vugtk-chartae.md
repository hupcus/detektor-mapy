# VÚGTK / Chartae Antiquae — návrh dopisu

> **Neodesláno. Návrh k odsouhlasení.**

## Adresát (ověřeno 2026-08-21)

Výzkumný ústav geodetický, topografický a kartografický, v. v. i.
Ústecká 98, 250 66 Zdiby
E-mail: `vugtk@vugtk.cz` · ID datové schránky: `7anp8u4` · tel. +420 226 802 302

Ve vyhledávání se objevil i kontakt `digitalizace_naki@vugtk.cz` (Centrum digitalizace) —
**před odesláním ověřit, že adresa ještě žije**; projekt NAKI skončil a adresa může být
mrtvá. Bezpečná varianta: poslat na `vugtk@vugtk.cz` s prosbou o předání pracovišti,
které Virtuální mapovou sbírku spravuje.

## Proč je tenhle dopis ze všech nejdůležitější

Chartae Antiquae je **jediný zdroj celorepublikových vojenských mapování ve Web
Mercatoru**, který máme. Bez něj se pro veřejnou verzi vrací jen offline PMTiles, které
si každý musí vyrobit sám na desktopu s GDALem — což je pro cílovou skupinu konec.
Zároveň VÚGTK podmínky užití nikde nepublikuje, takže bez odpovědi platí nejpřísnější
výklad a vrstvy zůstanou „jen živě", bez hromadného stahování.

Dotčené vrstvy: `muller_cechy`, `muller_morava`, `vm1`, `vm2_online`, `vm3_topo`
(šablona `chartae-antiquae.cz/TMS/<id>/{z}/{x}/{y}`).

---

## Text

**Předmět:** Dotaz na podmínky užití dlaždic z Chartae Antiquae v nekomerční aplikaci

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**. Ukazuje amatérským hledačům s detektory kovů historické mapy
nad jejich aktuální polohou v terénu — tedy přesně to, k čemu jsou staré mapy člověku
v krajině dobré. Aplikace je a zůstane zdarma, bez reklam, bez jakéhokoli zpeněžení
a bez sběru dat o uživatelích; zdrojový kód je veřejný na
`github.com/hupcus/detektor-mapy`.

Aplikace záměrně **odrazuje od nelegálního hledání**: zobrazuje území s archeologickými
nálezy podle podkladů NPÚ a upozorňuje na ohlašovací povinnost i na to, že vyhledávání
archeologických nálezů bez oprávnění je podle zákona č. 20/1987 Sb. protiprávní.

Ve Virtuální mapové sbírce Chartae Antiquae jsou georeferencované mozaiky, které jsou
pro tenhle účel jediné svého druhu — celorepublikové a rovnou ve Web Mercatoru:
Müllerova mapa Čech a Moravy a I., II. a III. vojenské mapování. Zobrazuji je jako
dlaždice ze šablony `chartae-antiquae.cz/TMS/<vrstva>/{z}/{x}/{y}`.

Podmínky užití jsem na webu nenašel, proto se ptám přímo. Zajímají mě tři různé věci,
protože každá znamená pro vaše servery něco jiného:

1. **Živé zobrazení** dlaždic ve výřezu, který si uživatel právě prohlíží.
2. **Uložení již zobrazených dlaždic v telefonu uživatele**, aby mapa fungovala i bez
   signálu — v lese obvykle signál není a bez toho je aplikace v terénu k ničemu. Data
   zůstávají v zařízení uživatele a nikam se dál nešíří; pro vaše servery to znamená,
   že tentýž uživatel tutéž dlaždici podruhé nestáhne, takže zátěž spíš klesá.
3. **Stažení zvolené oblasti dopředu** na žádost uživatele (řádově tisíce až desítky
   tisíc dlaždic v jednom sezení). Chápu, že tohle je z celé trojice nejcitlivější;
   **zapnu ji jen pokud to výslovně dovolíte** a rád ji omezím stropem, který určíte.

Co dodržujeme bez ohledu na odpověď: každý dotaz nese identifikaci
`DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`, takže nás v logu poznáte
a víte, kam napsat; nejvýše 4 souběžné dotazy na jeden server; při odpovědi 429 nebo 503
se aplikace na exponenciálně rostoucí dobu odmlčí, respektuje hlavičku `Retry-After`
a dotaz neopakuje.

Ještě dvě věci:

4. Jakou **atribuci** si u těchto vrstev přejete? Teď uvádím
   „© VÚGTK — Chartae Antiquae" u vrstvy i v sekci „O aplikaci"; rád to změním na
   znění, které si určíte, včetně případné citace původního vlastníka podkladu.
5. Pokud vám kterýkoli bod nevyhovuje, dejte prosím vědět — vrstvu vypnu nebo úplně
   odstraním. Raději se zeptám dopředu, než abyste to řešili blokováním.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_
