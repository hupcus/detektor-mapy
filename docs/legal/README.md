# docs/legal — podmínky poskytovatelů mapových dat

> Úkol **F7-6 (#40)**, blok C1 v `docs/VEREJNE_VYDANI.md`.
> Cíl: mít **písemně**, co smí veřejně šířená aplikace s cizími mapovými službami dělat.

## Pravidlo, které platí, dokud odpověď nedorazí

**Bez písemné odpovědi se vrstva chová podle nejpřísnějšího výkladu.** To prakticky znamená:
živé prohlížení ano (službu si stejně otevře každý v prohlížeči), cache z běžného
prohlížení zatím ano (je to technicky nutná součást zobrazení a šetří zdroje
poskytovatele), **hromadné stažení oblasti ne**.

## Stav (aktualizovat po každé odpovědi)

| Komu | Čeho se týká | Odesláno | Odpověď | Závěr pro veřejné vydání |
|---|---|---|---|---|
| ČÚZK | ZTM, ortofoto, DMR 5G, archiv ÚAZK | — | — | čeká |
| VÚGTK / Chartae Antiquae | Müller, I.–III. VM | — | — | čeká |
| NPÚ | ÚAN polygony | — | — | čeká |
| Jihočeský kraj | císařské otisky | — | — | čeká |
| Moravskoslezský kraj | císařské otisky | — | — | čeká |
| Karlovarský kraj | císařské otisky | — | — | čeká |
| CENIA | II./III. VM (S-JTSK) | — | — | nízká priorita, nahrazeno chartae |
| OSMF | tile.openstreetmap.org | **netřeba se ptát** | politika je veřejná, viz níže | **default podklad je nutné vyměnit** |

## OSM — jediná věc, která je už teď rozhodnutá

Tile Usage Policy OSMF (ověřeno 2026-08-21 na
<https://operations.osmfoundation.org/policies/tiles/>) mluví jasně a odpověď od nikoho
nepotřebujeme:

- **Vyžaduje** jednoznačný User-Agent se jménem aplikace a kontaktem — to už splňujeme
  (`cz/hh/detektormapy/net/PoliteHttp.kt`).
- **Vyžaduje** lokální cachování dlaždic — což děláme, takže tahle část je v pořádku
  a je pro poskytovatele spíš úlevou.
- **Zakazuje** dopředné stahování větších oblastí a více zoomů, stavbu dlaždicových
  archivů (výslovně jmenuje `.mbtiles`) a plošné scany, a to i pro funkce typu
  „stáhni oblast pro offline". To je přesně **A2 (#31)**.

Důsledek: `tile.openstreetmap.org` **nesmí zůstat výchozím podkladem** veřejně šířené
aplikace a nesmí být cílem hromadného stahování. Řeší **A5 / #9** (vlastní vektorový
podklad, nebo vlastní klíč u placené/bezplatné tier služby). Dokud to neplatí,
nevydávat veřejně.

## Jak archivovat odpovědi

Jeden soubor na instituci, pojmenovaný `odpoved-<instituce>-<RRRR-MM-DD>.md`, a v něm:
datum, kdo odpověděl (jméno, funkce, odbor), **plné znění bez zkracování**, a až pod tím
náš výklad. Výklad se od citace odděluje nadpisem, aby se za rok nedalo splést, co řekli
oni a co jsme si domysleli my.

Po každé odpovědi:
1. doplnit tabulku výše,
2. doplnit sloupec „veřejné šíření" v `docs/DATA_SOURCES.md`,
3. u vrstev bez souhlasu k hromadnému stahování nastavit příznak v katalogu (A2).
