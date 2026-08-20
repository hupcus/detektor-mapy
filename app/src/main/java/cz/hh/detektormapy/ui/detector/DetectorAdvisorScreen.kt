package cz.hh.detektormapy.ui.detector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.model.SoilCondition
import cz.hh.detektormapy.data.model.Terrain
import cz.hh.detektormapy.detector.NoktaLegendPresets
import cz.hh.detektormapy.detector.PresetMatch
import cz.hh.detektormapy.detector.PresetRanking
import cz.hh.detektormapy.ui.nav.Routes
import java.util.Locale

/**
 * The advisor: two questions in, the user's own presets ranked out.
 *
 * The honesty rules this screen is built around, in order of importance:
 *  1. It never invents a setting. Everything specific on screen was typed in by the user.
 *  2. It never claims to know the terrain. There is no offline land-cover data, so the chips
 *     start on whatever was picked last and the screen says nothing about where it thinks it is.
 *  3. The soil estimate is labelled as a weather-model estimate on a coarse grid, and can be
 *     overridden by the person actually standing on the ground.
 *  4. Offline, an empty library and a missing GPS fix are all normal -- each renders as a
 *     sentence, none of them as an error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorAdvisorScreen(navController: NavHostController) {
    val viewModel: DetectorAdvisorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Rádce nastavení") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Načíst znovu")
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
            TerrainCard(selected = state.terrain, onSelect = viewModel::selectTerrain)
            SoilCard(state = state, onOverride = viewModel::overrideSoil)
            PresetsCard(
                state = state,
                onOpenProfiles = { navController.navigate(Routes.DETECTOR_PROFILES) },
            )
            StartupRoutineCard()
        }
    }
}

@Composable
private fun TerrainCard(selected: Terrain, onSelect: (Terrain) -> Unit) {
    AdvisorCard("Kde jsi") {
        Text(
            text = "Terén vybíráš ty. Aplikace nemá offline data o krajině, takže by ho " +
                "musela hádat — a hádání zabalené do jistoty je horší než otázka.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Terrain.entries.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

@Composable
private fun SoilCard(state: DetectorAdvisorUiState, onOverride: (SoilCondition?) -> Unit) {
    AdvisorCard("Stav půdy") {
        when {
            state.weatherStatus == WeatherStatus.LOADING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Zjišťuji počasí…", style = MaterialTheme.typography.bodySmall)
                }
            }

            state.weatherStatus == WeatherStatus.UNAVAILABLE && !state.hasLocation -> {
                Text(
                    text = "Poloha nedostupná, takže ani počasí. Vyber stav půdy ručně.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.weatherStatus == WeatherStatus.UNAVAILABLE -> {
                Text(
                    text = "Počasí nedostupné. Vyber stav půdy ručně.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    text = "Odhad z modelu: ${state.estimatedSoil?.label ?: "—"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = modelDetail(state),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Je to model počasí na síti zhruba 11 km, ne měření tvého pole. " +
                        "Když to na místě vypadá jinak, přepiš to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoilCondition.entries.forEach { option ->
                FilterChip(
                    selected = state.soil == option,
                    onClick = { onOverride(if (state.soilOverride == option) null else option) },
                    label = { Text(option.label) },
                )
            }
        }
        if (state.soilOverride != null) {
            TextButton(onClick = { onOverride(null) }) { Text("Zpět na odhad z počasí") }
        }
    }
}

/** The raw numbers behind the estimate, so the user can disagree with the reasoning, not just the verdict. */
private fun modelDetail(state: DetectorAdvisorUiState): String {
    val parts = mutableListOf<String>()
    state.soilMoistureM3M3?.let {
        parts += "vlhkost půdy 3–9 cm: ${String.format(Locale.ROOT, "%.2f", it)} m³/m³"
    }
    state.recentRainMm?.let {
        parts += "srážky za 3 dny: ${String.format(Locale.ROOT, "%.1f", it)} mm"
    }
    return if (parts.isEmpty()) "Model nevrátil žádná data." else parts.joinToString(" • ")
}

@Composable
private fun PresetsCard(state: DetectorAdvisorUiState, onOpenProfiles: () -> Unit) {
    AdvisorCard("Tvoje presety") {
        if (!state.hasPresets) {
            Text(
                text = "Zatím nemáš uložený žádný preset, takže není z čeho vybírat. " +
                    "Aplikace ti nastavení nevymyslí — nezná tvůj stroj.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenProfiles, modifier = Modifier.fillMaxWidth()) {
                Text("Přidat detektor a preset")
            }
        } else {
            state.ranked.forEachIndexed { index, match ->
                if (index > 0) HorizontalDivider()
                PresetMatchRow(match)
            }
            TextButton(onClick = onOpenProfiles) { Text("Upravit presety") }
        }
    }
}

@Composable
private fun PresetMatchRow(match: PresetMatch) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "${match.preset.name} — ${match.detectorName}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (match.score == PresetRanking.SCORE_TERRAIN_AND_SOIL) {
                FontWeight.Bold
            } else {
                FontWeight.Normal
            },
        )
        Text(
            text = match.reason,
            style = MaterialTheme.typography.bodySmall,
            color = if (match.score == PresetRanking.SCORE_NONE) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        val values = presetValueLine(match.preset)
        if (values.isNotBlank()) {
            Text(values, style = MaterialTheme.typography.bodySmall)
        }
        if (match.preset.notes.isNotBlank()) {
            Text(
                text = match.preset.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The routine that comes before any preset: noise cancel, ground balance, walk a bit, and only
 * then start touching numbers. It is the part people skip and then blame the settings for.
 */
@Composable
private fun StartupRoutineCard() {
    AdvisorCard("Než začneš na nové lokalitě") {
        NoktaLegendPresets.STARTUP_ROUTINE.forEachIndexed { index, step ->
            Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Nehonit maximální citlivost. Klidný detektor je v reálné půdě užitečnější " +
                "než nestabilní na maximu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvisorCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}
