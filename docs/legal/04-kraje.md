# Kraje (císařské otisky) — návrh dopisu

> **Neodesláno. Návrh k odsouhlasení.** Jeden text, tři adresáti — liší se jen názvem
> služby a odstavcem o obchodním užití, který je nutný jen u Jihočeského kraje.

## Adresáti (ověřeno 2026-08-21)

| Kraj | Vrstva | Adresa | E-podatelna | Datová schránka |
|---|---|---|---|---|
| Jihočeský | `cisarske_jc` | U Zimního stadionu 1952/2, 370 01 České Budějovice | `posta@kraj-jihocesky.cz` | `kdib3rr` |
| Moravskoslezský | `cisarske_msk` | 28. října 2771/117, 702 00 Ostrava | `posta@msk.cz` | `8x6bxsd` |
| Karlovarský | `cisarske_kvk` | Závodní 353/88, 360 06 Karlovy Vary | `epodatelna@kr-karlovarsky.cz` (max. 10 MB) | `siqbxt2` |

## Co je u kterého kraje jinak

- **Jihočeský** u WMTS otisků výslovně uvádí, že opětovné užití pro **obchodní účely je
  zamezeno** (ochranné znaky ČÚZK). Naše aplikace je nekomerční, takže bychom měli být
  v pořádku — ale „nekomerční open source, který si někdo může forknout" je přesně ten
  případ, kde se výklad rozchází, a proto se ptáme.
- **Karlovarský** službu nikde neinzeruje; našli jsme ji přes položku v AGOL Experience.
  V dopise to stojí za zmínku — ať vědí, že je někdo používá, a mohou říct ne.
- **Moravskoslezský** podmínky nepublikuje.

---

## Text

**Předmět:** Dotaz na podmínky užití mapové služby císařských otisků

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**, která amatérským hledačům s detektory kovů zobrazuje historické
mapy nad jejich aktuální polohou v terénu. Aplikace je a zůstane zdarma, bez reklam,
bez jakéhokoli zpeněžení a bez sběru dat o uživatelích; zdrojový kód je veřejný na
`github.com/hupcus/detektor-mapy`.

Aplikace záměrně **odrazuje od nelegálního hledání**: zobrazuje území s archeologickými
nálezy podle podkladů NPÚ a upozorňuje na ohlašovací povinnost i na to, že vyhledávání
archeologických nálezů bez oprávnění je podle zákona č. 20/1987 Sb. protiprávní.

Rád bych se zeptal na podmínky užití vaší mapové služby **císařských otisků stabilního
katastru** _(doplnit přesný název a URL služby daného kraje)_. Konkrétně:

1. **Živé zobrazení** dlaždic ve výřezu, který si uživatel právě prohlíží.
2. **Uložení již zobrazených dlaždic v telefonu uživatele**, aby mapa fungovala i bez
   signálu. Data zůstávají v jeho zařízení a nikam se dál nešíří; pro váš server to
   znamená, že tentýž uživatel tutéž dlaždici podruhé nestáhne.
3. **Stažení zvolené oblasti dopředu** na žádost uživatele. Tuhle funkci zapneme jen
   tam, kde ji poskytovatel výslovně dovolí.

_(Jen pro Jihočeský kraj:)_ U vaší služby jsem zaznamenal podmínku, že opětovné užití
pro obchodní účely je zamezeno z důvodu ochranných znaků ČÚZK. Aplikace je nekomerční —
zdarma, bez reklam a bez jakéhokoli zpeněžení. Protože je ale zdrojový kód veřejný
a teoreticky si ho může kdokoli upravit, rád bych měl váš výklad písemně: považujete
takové užití za přípustné?

_(Jen pro Karlovarský kraj:)_ Vaši službu jsem našel přes položku ve vaší aplikaci
v prostředí ArcGIS Online; na webu kraje jsem odkaz na ni nenašel. Pokud není určena
k použití třetími stranami, dejte prosím vědět a vrstvu z aplikace odstraním.

Co dodržujeme bez ohledu na odpověď: každý dotaz nese identifikaci
`DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`; nejvýše 4 souběžné dotazy na
jeden server; při odpovědi 429 nebo 503 se aplikace na exponenciálně rostoucí dobu
odmlčí a dotaz neopakuje; atribuce je uvedena u vrstvy i v sekci „O aplikaci".

Prosím také o **přesné znění atribuce**, které si u této vrstvy přejete.

Pokud vám kterýkoli z bodů nevyhovuje, dejte prosím vědět — funkci vypnu, omezím,
nebo vrstvu z aplikace odstraním.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_
