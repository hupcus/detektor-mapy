# DetektorMapy

Soukromá Android aplikace pro hledání s detektorem kovů v ČR. Historické mapy jako
překryvné vrstvy nad OpenStreetMap, LiDAR reliéf, evidence nálezů — **celé offline**.

- **Historické mapy ČR jako overlay** — II. a III. vojenské mapování, císařské otisky;
  plynulé přepínání a nastavitelná průhlednost.
- **Ruční doladění georeference přímo v terénu** — dvouprstové gesto sladí posunutou
  mapu na rybník nebo kostel do půl minuty; korekce se uloží pro danou oblast.
- **LiDAR reliéf (DMR 5G)** — úvozy, milíře, valy, zaniklé cesty pod korunami stromů.
- **Nálezy a místa** — foto, GPS, kategorie, hloubka; waypointy a prohledané zóny.
- **Vrstva ÚAN** (NPÚ) pro právní kontext.
- **Plně offline v terénu.** Online jen doma při přípravě dat.

Jeden uživatel, žádný Google Play, žádné účty, žádný sync na server.

## Screenshot

<!-- TODO: doplnit screenshot mapy s II. VM overlay nad OSM -->
_(placeholder — doplní se po Fázi 1)_

## Build

Potřebuješ **JDK 21** a Android SDK. Novější systémová Java (25/26) není podporovaná AGP.

```bash
./gradlew assembleDebug
```

Debug APK najdeš v `app/build/outputs/apk/debug/`.

Další užitečné:

```bash
./gradlew testDebugUnitTest   # unit testy
./gradlew lintDebug           # Android Lint
```

> `gradle.properties` obsahuje `org.gradle.java.home` s lokální cestou k JDK 21.
> Pokud máš JDK jinde, přepiš si ji. CI si tenhle řádek odstraňuje sama
> (viz `.github/workflows/ci.yml`).

## Příprava dat

Mapové vrstvy se **nekomitují do repozitáře** (velikost + licence). Připravují se
na počítači Python pipeline pod `tools/`:

```bash
python3 -m pip install -r tools/requirements.txt
python3 tools/sources.py             # přehled dostupných zdrojů
python3 tools/check_endpoints.py     # ověření dostupnosti služeb
```

Kompletní návod (bbox → dlaždice → PMTiles) je v **[`tools/README.md`](tools/README.md)**.
Přehled zdrojů, licencí a atribucí v **[`docs/DATA_SOURCES.md`](docs/DATA_SOURCES.md)**.

Hotové `.pmtiles` soubory plus `layers.json` se kopírují do telefonu (USB / Syncthing) do:

```
Android/data/cz.hh.detektormapy/files/layers/
```

Přidání nové mapy = soubor + řádek v `layers.json`. Žádný nový release.

## Instalace APK

1. Stáhni APK z **GitHub Releases** (produkuje ho `release.yml` na tag `v*`).
2. Nainstaluj sideloadem — v telefonu povol instalaci z neznámých zdrojů pro prohlížeč
   nebo správce souborů.
3. **Doporučeno:** nainstaluj [Obtainium](https://github.com/ImranR98/Obtainium),
   přidej tenhle repozitář a aktualizace z Releases se budou stahovat samy.

## Dokumentace

| Dokument | O čem je |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | moduly, datový tok vrstev, lokální dlaždicový server, kalibrace, Room schéma, vlákna |
| [`docs/DATA_SOURCES.md`](docs/DATA_SOURCES.md) | mapové služby, URL, CRS, zoomy, licence, jak přidat zdroj |
| [`docs/FIELD_GUIDE.md`](docs/FIELD_GUIDE.md) | terénní příručka — příprava, ovládání, čtení reliéfu, právo, troubleshooting |
| [`tools/README.md`](tools/README.md) | desktop pipeline: příprava dat pro nový region |
| [`PLAN.md`](PLAN.md) | zadání projektu, fáze, backlog |
| [`handoff.md`](handoff.md) | živá rozhodnutí, toolchain, stav fází |
| [`CHANGELOG.md`](CHANGELOG.md) | historie verzí |

## Licence a data

Prohlížecí služby ČÚZK a CENIA jsou zdarma, ale **nejsou určené ke komerčnímu užití
a redistribuce vyžaduje souhlas**. Aplikace je výhradně pro osobní potřebu jednoho
uživatele, dlaždice se cachují lokálně.

> ⚠️ **Nikdy nepublikovat APK ani PMTiles s daty veřejně.**

Atribuce patří do about screen aplikace: **© ČÚZK**, **© CENIA**,
**© Rakouský státní archiv / Laboratoř geoinformatiky UJEP**,
**© OpenStreetMap contributors**, **© Národní památkový ústav**.

Podrobnosti v [`docs/DATA_SOURCES.md`](docs/DATA_SOURCES.md).
