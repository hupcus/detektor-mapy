package cz.hh.detektormapy.map

/**
 * Seed catalogue written to `layers.json` on first launch.
 *
 * Only online sources are listed, because a fresh install has no PMTiles on disk yet.
 * The user (or `tools/fetch_tiles.py`) drops archives into the layers directory and adds
 * a matching entry -- the file is meant to be edited by hand, so it stays readable.
 *
 * Endpoints come from PLAN.md section 3. Attribution strings are shown in the about screen.
 */
object DefaultLayers {

    const val CATALOG_FILE = "layers.json"

    const val ATTR_CENIA = "© CENIA / Rakouský státní archiv"
    const val ATTR_CUZK = "© ČÚZK"
    const val ATTR_OSM = "© OpenStreetMap přispěvatelé"
    const val ATTR_UJEP = "© Laboratoř geoinformatiky UJEP"
    const val ATTR_NPU = "© NPÚ"
    const val ATTR_CHARTAE = "© VÚGTK / Chartae-antiquae.cz"

    val BASEMAP_OSM = LayerDef(
        id = "osm",
        title = "OpenStreetMap",
        kind = LayerKind.XYZ,
        source = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        attribution = ATTR_OSM,
        minZoom = 0,
        maxZoom = 19,
        isBasemap = true,
        enabledByDefault = true,
        defaultOpacity = 1f,
        note = "Online podklad. Offline verzi nahraď vektorovými PMTiles (F1-5).",
    )

    val BASEMAP_ZTM = LayerDef(
        id = "ztm",
        title = "Základní topografická mapa (ČÚZK)",
        kind = LayerKind.XYZ,
        source = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}",
        attribution = ATTR_CUZK,
        minZoom = 4,
        maxZoom = 19,
        isBasemap = true,
        defaultOpacity = 1f,
        bounds = listOf(11.9, 48.4, 19.0, 51.2),
        note = "Vrstevnice, lesní cesty, kóty — v terénu čitelnější než OSM. " +
            "Nativní Web Mercator cache, ověřeno 2026-08-20.",
    )

    val catalog = LayerCatalog(
        version = 2,
        layers = listOf(
            BASEMAP_OSM,
            BASEMAP_ZTM,
            LayerDef(
                id = "muller_cechy",
                title = "Müllerova mapa Čech (1720)",
                kind = LayerKind.XYZ,
                source = "https://www.chartae-antiquae.cz/TMS/MullerC/{z}/{x}/{y}",
                attribution = ATTR_CHARTAE,
                minZoom = 5,
                maxZoom = 14,
                order = 7,
                defaultOpacity = 0.75f,
                bounds = listOf(12.0, 48.5, 16.5, 51.1),
                note = "Nejstarší podrobná mapa Čech (1:132 000). Georeference je přibližná — " +
                    "mapa vznikla před triangulací.",
            ),
            LayerDef(
                id = "muller_morava",
                title = "Müllerova mapa Moravy (1716)",
                kind = LayerKind.XYZ,
                source = "https://www.chartae-antiquae.cz/TMS/MullerM/{z}/{x}/{y}",
                attribution = ATTR_CHARTAE,
                minZoom = 5,
                maxZoom = 14,
                order = 8,
                defaultOpacity = 0.75f,
                bounds = listOf(15.0, 48.6, 18.9, 50.5),
                note = "Moravský protějšek Müllerovy mapy Čech, stejná přibližná georeference.",
            ),
            LayerDef(
                id = "vm1",
                title = "I. vojenské mapování (1764–68)",
                kind = LayerKind.XYZ,
                source = "https://www.chartae-antiquae.cz/TMS/Military1/{z}/{x}/{y}",
                attribution = ATTR_CHARTAE,
                minZoom = 5,
                maxZoom = 15,
                order = 9,
                defaultOpacity = 0.75f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                manualAlignment = true,
                note = "Mapováno od oka, bez trigonometrické sítě — poloha může být vedle " +
                    "i o stovky metrů. Pro přesné hledání si list přilož přes " +
                    "Vrstvy → Přiložit sken… a zarovnej ručně.",
            ),
            LayerDef(
                id = "vm2",
                title = "II. vojenské mapování (1836–52)",
                kind = LayerKind.PMTILES,
                source = "vm2.pmtiles",
                attribution = ATTR_CENIA,
                minZoom = 8,
                maxZoom = 15,
                order = 10,
                defaultOpacity = 0.75f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Klíčová vrstva pro detektoráře. CENIA publikuje dlaždice jen v S-JTSK, " +
                    "takže online je použít nelze — vygeneruj PMTiles: " +
                    "tools/fetch_tiles.py --source ii_vm  →  tools/build_pmtiles.py",
            ),
            LayerDef(
                id = "vm3",
                title = "III. vojenské mapování — speciálky 1:75 000",
                kind = LayerKind.PMTILES,
                source = "vm3.pmtiles",
                attribution = ATTR_CENIA,
                minZoom = 8,
                maxZoom = 15,
                order = 11,
                defaultOpacity = 0.75f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Stejně jako II. VM jen offline: tools/fetch_tiles.py --source iii_vm",
            ),
            LayerDef(
                id = "vm2_online",
                title = "II. vojenské mapování (1836–52) — online",
                kind = LayerKind.XYZ,
                source = "https://www.chartae-antiquae.cz/TMS/Military2/{z}/{x}/{y}",
                attribution = ATTR_CHARTAE,
                minZoom = 5,
                maxZoom = 16,
                order = 15,
                defaultOpacity = 0.75f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Funguje hned, bez stahování. Offline vrstva z CENIA (vm2.pmtiles) " +
                    "má o něco věrnější barvy — tahle je záloha a rychlý start.",
            ),
            LayerDef(
                id = "vm3_topo",
                title = "III. vojenské mapování 1:25 000 — online",
                kind = LayerKind.XYZ,
                source = "https://www.chartae-antiquae.cz/TMS/Military3/{z}/{x}/{y}",
                attribution = ATTR_CHARTAE,
                minZoom = 5,
                maxZoom = 16,
                order = 16,
                defaultOpacity = 0.75f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Topografické sekce — nejpodrobnější z vojenských mapování. " +
                    "CENIA má jen speciálky 1:75 000, tohle je jinde nedostupný detail.",
            ),
            LayerDef(
                id = "dmr5g",
                title = "DMR 5G — stínovaný reliéf (LiDAR)",
                kind = LayerKind.WMS,
                source = "https://ags.cuzk.gov.cz/arcgis2/services/dmr5g/ImageServer/WMSServer",
                wmsLayers = "dmr5g:GrayscaleHillshade",
                wmsFormat = "image/png",
                wmsVersion = "1.3.0",
                attribution = ATTR_CUZK,
                minZoom = 10,
                maxZoom = 18,
                order = 20,
                defaultOpacity = 0.6f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Úvozy, milíře, valy, zaniklé cesty. Ostřejší vlastní render viz tools/dmr5g_hillshade.py.",
            ),
            LayerDef(
                id = "ortofoto",
                title = "Ortofoto ČR",
                kind = LayerKind.XYZ,
                source = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/" +
                    "MapServer/tile/{z}/{y}/{x}",
                attribution = ATTR_CUZK,
                minZoom = 7,
                maxZoom = 20,
                order = 5,
                defaultOpacity = 1f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Referenční podklad pro kalibraci (Režim B). ORTOFOTO_WM je " +
                    "předgenerovaná cache přímo ve Web Mercatoru (ověřeno 2026-08-20) — " +
                    "rychlejší než dřívější export z S-JTSK služby.",
            ),
            LayerDef(
                id = "cisarske_jc",
                title = "Císařské otisky — Jihočeský kraj",
                kind = LayerKind.ARCGIS,
                source = "https://gis.kraj-jihocesky.cz/arcgis/rest/services/podkladove/" +
                    "cisarske_otisky/MapServer",
                attribution = "© Jihočeský kraj",
                minZoom = 8,
                maxZoom = 18,
                order = 12,
                defaultOpacity = 0.8f,
                bounds = listOf(13.4, 48.5, 15.6, 49.6),
            ),
            LayerDef(
                id = "uan",
                title = "ÚAN — území s archeologickými nálezy",
                kind = LayerKind.GEOJSON,
                source = "uan.geojson",
                attribution = ATTR_NPU,
                minZoom = 8,
                maxZoom = 19,
                order = 30,
                defaultOpacity = 0.35f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                isProtectedArea = true,
                note = "Stáhni přes tools/uan_fetch.py a ulož jako layers/uan.geojson. " +
                    "V ÚAN I/II je hledání bez povolení zakázané.",
            ),
            LayerDef(
                id = "cisarske_msk",
                title = "Císařské otisky — Moravskoslezský kraj",
                kind = LayerKind.ARCGIS,
                source = "https://gis2.msk.cz/arcgis/rest/services/podklad/" +
                    "podklad_cis_otisky/MapServer",
                attribution = "© Moravskoslezský kraj",
                minZoom = 8,
                maxZoom = 18,
                order = 13,
                defaultOpacity = 0.8f,
                bounds = listOf(16.8, 49.3, 18.9, 50.4),
            ),
            LayerDef(
                id = "cisarske_kvk",
                title = "Císařské otisky — Karlovarský kraj",
                kind = LayerKind.ARCGIS,
                source = "https://geo-ags.kr-karlovarsky.cz/arcgis/rest/services/Image/" +
                    "CisarskeOtisky/MapServer",
                attribution = "© Karlovarský kraj",
                minZoom = 8,
                maxZoom = 18,
                order = 14,
                defaultOpacity = 0.8f,
                bounds = listOf(12.0, 49.8, 13.5, 50.5),
            ),
        ),
    )
}
