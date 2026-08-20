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

    val catalog = LayerCatalog(
        version = 1,
        layers = listOf(
            BASEMAP_OSM,
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
                kind = LayerKind.ARCGIS,
                source = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO/MapServer",
                arcgisFormat = "jpg",
                attribution = ATTR_CUZK,
                minZoom = 7,
                maxZoom = 19,
                order = 5,
                defaultOpacity = 1f,
                bounds = listOf(11.9, 48.4, 19.0, 51.2),
                note = "Referenční podklad pro kalibraci (Režim B). Služba je v S-JTSK, " +
                    "ArcGIS ji ale přepočítá do Web Mercatoru sám.",
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
        ),
    )
}
