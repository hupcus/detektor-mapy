# OSMF — dopis se (zatím) neposílá

> **Nic k odsouhlasení. Tady je rozhodnutí, ne dotaz.**

## Proč se neptáme

Tile Usage Policy je veřejná a jednoznačná (ověřeno 2026-08-21 na
<https://operations.osmfoundation.org/policies/tiles/>). Ptát se, jestli smíme dělat to,
co politika jmenovitě zakazuje, je ztráta času jejich i našeho.

**Co politika vyžaduje a my už splňujeme:**

- jednoznačný, stabilní User-Agent se jménem aplikace a kontaktem —
  `DetektorMapy/<verze> (github.com/hupcus/detektor-mapy)`;
- lokální cachování dlaždic (politika ho výslovně **vyžaduje**, minimálně 7 dní, pokud
  klient neumí číst hlavičky) — naše write-through cache je z tohoto pohledu v pořádku
  a serverům spíš ulevuje;
- nikdy neposílat `Cache-Control: no-cache` — neposíláme.

**Co politika zakazuje a my to plánovali:**

- dopředné stahování větších oblastí a více zoomů,
- stavbu dlaždicových archivů (`.zip`, **`.mbtiles`**) k pozdějšímu šíření,
- automatizované scany přes široké bboxy, zvlášť na z ≥ 14,
- a výslovně funkce typu **„stáhni město/zemi pro offline"** nebo „ulož oblast na
  později".

Poslední odrážka je jméno naší funkce **A2 (#31)**.

## Důsledky, které z toho plynou

1. **`tile.openstreetmap.org` nesmí zůstat výchozím podkladem** veřejně šířené aplikace.
   Dnes jím je (`DefaultLayers.kt`, vrstva `osm`, `enabledByDefault = true`).
2. **OSM nesmí být cílem hromadného stahování** (A2), bez ohledu na to, jak dopadnou
   ostatní poskytovatelé.
3. Běžná cache z prohlížení u OSM zůstat může — politika ji vyžaduje.
4. Řešením je **A5 / #9**: vlastní vektorový podklad z OSM dat (dlaždice si servírujeme
   sami, licence ODbL to dovoluje), nebo vlastní klíč u služby, která to má v podmínkách.

**Bez toho veřejné vydání nevydávat.** Je to jediná položka M1–M3, u které porušení
pravidel začíná první minutou provozu, ne až u nějakého okrajového případu.

## Kdyby přece jen bylo potřeba psát

Operations Working Group: `operations@osmfoundation.org`. Použít jen tehdy, když bude
konkrétní technický problém k projednání — ne na dotaz, jehož odpověď je v politice.
