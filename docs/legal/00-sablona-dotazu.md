# Šablona dotazu — společný základ

> Návrh k odsouhlasení. **Neodesláno.** Jednotlivé dopisy níže v `01-` až `06-` z téhle
> šablony vycházejí a liší se jen tím, co konkrétně od dané služby chceme.

## Zásady, podle kterých jsou dopisy napsané

1. **Ptát se na tři věci zvlášť**, ne na „použití" obecně. Poskytovatel má u každé jiný
   pocit a smíchané dohromady dostaneme buď paušální ne, nebo nejednoznačné ano:
   - *živé prohlížení* — dlaždice se stáhne, zobrazí, zahodí;
   - *cache v telefonu* — dlaždice, kterou uživatel viděl, zůstane v jeho zařízení;
   - *hromadné stažení oblasti uživatelem* — desítky tisíc dlaždic dopředu.
2. **Říct rovnou, jak se chováme slušně.** Identifikace User-Agentem, strop souběžných
   dotazů, backoff. To je jediná věc, kterou provozovatel prakticky řeší.
3. **Nabídnout, ne žádat o výjimku.** Nabídnout rate limit, nabídnout stažení vrstvy,
   pokud se jim to nelíbí.
4. **Nezastírat, že je to open source.** Kód bude veřejný, takže se stejně dozví.
5. **Krátce.** Úředník má na dotaz od amatéra minutu, ne půl hodiny.

## Kostra

**Předmět:** Dotaz na podmínky užití prohlížecích služeb v nekomerční aplikaci

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**, která amatérským hledačům s detektory kovů zobrazuje nad
jejich aktuální polohou historické mapy. Aplikace je a zůstane **zdarma, bez reklam,
bez jakéhokoli zpeněžení a bez sběru jakýchkoli dat o uživatelích**; zdrojový kód je
veřejný na `github.com/hupcus/detektor-mapy`.

Aplikace má výslovně **odrazovat od nelegálního hledání**: zobrazuje území
s archeologickými nálezy a kulturní památky, upozorňuje na ohlašovací povinnost podle
zákona č. 20/1987 Sb. a na to, že hledání archeologických nálezů bez oprávnění je
protiprávní.

Rád bych se zeptal, zda a za jakých podmínek smí taková aplikace používat vaši službu
_(NÁZEV SLUŽBY, URL)_, konkrétně:

1. **Živé zobrazení** dlaždic ve výřezu, který si uživatel právě prohlíží.
2. **Uložení již zobrazených dlaždic v telefonu uživatele**, aby mapa fungovala i bez
   signálu. Data zůstávají v jeho zařízení a nikam se dál nešíří; z pohledu vaší služby
   to znamená, že tentýž uživatel tutéž dlaždici nestáhne podruhé.
3. **Stažení zvolené oblasti dopředu** (řádově tisíce až desítky tisíc dlaždic
   v jednom sezení). Tuhle funkci **zapneme jen pro služby, které to výslovně dovolí.**

Co už dodržujeme bez ohledu na odpověď:

- každý dotaz nese identifikaci `DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`,
  takže nás v logu poznáte a víte, kam napsat;
- nejvýše 4 souběžné dotazy na jeden server;
- při odpovědi 429 nebo 503 se aplikace na exponenciálně rostoucí dobu odmlčí,
  respektuje `Retry-After` a dotaz neopakuje;
- atribuce je uvedena přímo v aplikaci u vrstvy i v sekci „O aplikaci".

Pokud vám jakýkoli z bodů nevyhovuje, dejte prosím vědět — buď ho vypneme, nebo
nastavíme přísnější limit, nebo vrstvu z aplikace odstraníme. Rád se také přizpůsobím
konkrétnímu stropu, pokud ho určíte.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_

---

## Poznámka k odesílání

U úřadů použít datovou schránku nebo e-podatelnu — z e-podatelny přijde potvrzení
o doručení, což je pro archivaci v `docs/legal/` důležitější než rychlost. U VÚGTK
a u OSMF stačí běžný e-mail.
