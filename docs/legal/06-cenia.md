# CENIA — návrh dopisu (nízká priorita)

> **Neodesláno. Návrh k odsouhlasení.** Odeslat až po ostatních, nebo vůbec.

## Proč je to nejnižší priorita

Pro veřejnou verzi službu CENIA **nepotřebujeme**. Publikuje výhradně v EPSG:5514, takže
ji stejně nelze použít online (ověřeno: WMTS má jediný TileMatrixSet `jtsk:epsg:5514`,
WMS ohlašuje jen `SRS=EPSG:5514`), a obsahově ji nahradila Chartae Antiquae ve Web
Mercatoru. V aplikaci z ní zbývají jen **offline PMTiles**, které si uživatel vyrobí
sám desktopovou pipeline (`tools/fetch_tiles.py --source ii_vm`).

Ptát se má tedy smysl jedině na jednu věc: **jestli je v pořádku, aby si uživatel touto
pipeline stáhl výřez pro vlastní potřebu.** Kdyby VÚGTK odpověděl ne, tohle je záložní
cesta k II. a III. vojenskému mapování a bude potřeba mít ji ošetřenou.

Adresáta ověřit před odesláním — CENIA je příspěvková organizace MŽP, kontakt na
`cenia.cz`.

---

## Text

**Předmět:** Dotaz na podmínky stažení výřezu mapové služby pro osobní offline užití

Dobrý den,

jmenuji se Honza Hubka a vyvíjím ve volném čase nekomerční open source aplikaci pro
Android **DetektorMapy**, která amatérským hledačům s detektory kovů zobrazuje historické
mapy nad jejich aktuální polohou. Aplikace je zdarma, bez reklam a bez sběru dat
o uživatelích; zdrojový kód je veřejný na `github.com/hupcus/detektor-mapy`.

Součástí projektu je desktopový nástroj, kterým si uživatel může z vaší služby
II. a III. vojenského mapování stáhnout **výřez v rozsahu, který ho zajímá** (typicky
okolí bydliště, řádově desítky kilometrů čtverečních), a převést ho do offline archivu
pro vlastní telefon. Bez toho mapa v terénu bez signálu nefunguje.

Rád bych se zeptal:

1. Je takové **stažení výřezu pro osobní offline užití** z vaší strany přípustné?
   Pokud ano, existuje strop (počet dlaždic, denní limit, doporučená prodleva mezi
   dotazy), kterým se mám řídit?
2. Jakou **atribuci** si u těchto vrstev přejete? Dnes uvádím
   „© CENIA / Rakouský státní archiv, Laboratoř geoinformatiky UJEP".
3. Publikujete tato mapování i v EPSG:3857? Vaše WMTS nabízí pouze TileMatrixSet
   `jtsk:epsg:5514` a WMS ohlašuje jen `SRS=EPSG:5514`, takže je dnes nelze zobrazit
   přímo v mapě ve Web Mercatoru bez reprojekce na naší straně.

Nástroj dotazy neparalelizuje nad rámec čtyř souběžných spojení, identifikuje se
jako `DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)` a při odpovědi 429 nebo
503 se odmlčí.

Předem děkuji za odpověď.

S pozdravem
Honza Hubka
_(kontakt doplnit před odesláním)_
