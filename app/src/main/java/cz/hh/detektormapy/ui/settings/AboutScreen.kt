package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import cz.hh.detektormapy.BuildConfig

/**
 * About screen with the attribution list and the legal minimum (PLAN.md F5-3).
 *
 * This screen is not decoration: the map sources are usable only because they are credited here
 * (docs/DATA_SOURCES.md: "bez atribuce se zdroj nepoužívá"), and the four field rules below are
 * the ones that decide whether a find is a discovery or an offence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavHostController) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("O aplikaci") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup("Detektor mapy") {
                Text(
                    text = "Verze ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Offline mapy, historické podklady a deník nálezů pro hledání " +
                        "s detektorem kovů. Aplikace je určená výhradně pro osobní potřebu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Licenční upozornění", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Prohlížecí služby ČÚZK a CENIA jsou zdarma, ale nejsou určené ke " +
                            "komerčnímu užití a jejich redistribuce vyžaduje souhlas. Stažené " +
                            "dlaždice drž jen pro osobní potřebu.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Nikdy nepublikuj APK s daty veřejně.",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }

            SettingsGroup("Zdroje dat a atribuce") {
                AttributionRow(
                    "ČÚZK",
                    "Ortofoto ČR, DMR 5G (stínovaný reliéf), archiv ÚAZK — © ČÚZK",
                )
                AttributionRow(
                    "CENIA",
                    "II. a III. vojenské mapování přes mapcache — © CENIA",
                )
                AttributionRow(
                    "Laboratoř geoinformatiky UJEP / Rakouský státní archiv",
                    "Historická vojenská mapování (oldmaps) — © Rakouský státní archiv / " +
                        "Laboratoř geoinformatiky UJEP",
                )
                AttributionRow(
                    "OpenStreetMap přispěvatelé",
                    "Podkladová mapa — © OpenStreetMap contributors, ODbL",
                )
                AttributionRow(
                    "Národní památkový ústav",
                    "Vrstva ÚAN (území s archeologickými nálezy) — © NPÚ",
                )
                AttributionRow(
                    "Jihočeský kraj",
                    "Císařské otisky stabilního katastru — © Jihočeský kraj / ČÚZK",
                )
                AttributionRow(
                    "Moravskoslezský kraj",
                    "Císařské otisky stabilního katastru — © Moravskoslezský kraj / ČÚZK",
                )
                AttributionRow(
                    "MapLibre",
                    "Vykreslování map — MapLibre GL Native, BSD licence",
                )
            }

            SettingsGroup("Právní minimum") {
                LegalRule(
                    "1. Archeologický nález oznam do 2 dnů",
                    "Nález, který může být archeologický, oznam do 2 dnů muzeu nebo NPÚ. " +
                        "Aplikace ti drží datum, souřadnice i fotky — máš čím oznámení doložit.",
                )
                LegalRule(
                    "2. S místem nálezu 5 dní nemanipuluj",
                    "Od oznámení nech místo 5 dní beze změny, aby ho archeolog posoudil " +
                        "v původním stavu.",
                )
                LegalRule(
                    "3. Bez souhlasu vlastníka nikam",
                    "Na cizí pozemek jen se souhlasem vlastníka nebo hospodáře. U polí to " +
                        "bývá zemědělský podnik, ne majitel parcely.",
                )
                LegalRule(
                    "4. ÚAN I a II bez povolení ne",
                    "V územích s archeologickými nálezy kategorie I a II se nehledá. " +
                        "Vrstva ÚAN je v aplikaci právě proto, abys to viděl včas.",
                )
                Text(
                    text = "Podrobnosti v docs/FIELD_GUIDE.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttributionRow(source: String, detail: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(source, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegalRule(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
