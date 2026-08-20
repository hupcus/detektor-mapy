package cz.hh.detektormapy.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController

/**
 * The checklist to read before driving out (PLAN.md F5-2).
 *
 * Every line here is something that is either free to check at home and expensive to discover in
 * a field, or that decides whether the trip makes sense at all -- daylight left above all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreflightScreen(navController: NavHostController) {
    val viewModel: PreflightViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Pre-flight") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Obnovit")
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
            SettingsGroup("Poloha") {
                val fix = state.fix
                if (fix == null) {
                    Text(
                        text = when {
                            !state.locationPermission -> "Aplikace nemá povolenou polohu."
                            !state.gpsEnabled -> "GPS je vypnutá."
                            else -> "Čekám na první fix…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = String.format(CS_LOCALE, "%.6f, %.6f", fix.lat, fix.lon),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = "Přesnost: ${fix.accuracyM?.let { "±${it.toInt()} m" } ?: "neznámá"}" +
                            " • ${formatDateTime(fix.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup("Světlo") {
                InfoRow("Východ slunce", formatTime(state.sunriseMillis))
                InfoRow("Západ slunce", formatTime(state.sunsetMillis))
                InfoRow(
                    label = "Zbývá světla",
                    value = state.daylightLeftMs?.let {
                        if (it <= 0L) "už je po západu" else formatDuration(it)
                    } ?: "—",
                )
                if (state.fix == null) {
                    Text(
                        text = "Bez polohy se východ a západ nedají spočítat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup("Počasí") {
                val weather = state.weather
                when {
                    state.weatherLoading -> Text("Načítám…", style = MaterialTheme.typography.bodyMedium)

                    weather == null -> Text(
                        text = "počasí nedostupné",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        InfoRow(
                            "Teplota",
                            weather.temperatureC?.let { String.format(CS_LOCALE, "%.1f °C", it) } ?: "—",
                        )
                        InfoRow(
                            "Srážky",
                            weather.precipitationMm?.let { String.format(CS_LOCALE, "%.1f mm", it) } ?: "—",
                        )
                        InfoRow(
                            "Vítr",
                            weather.windKmh?.let { String.format(CS_LOCALE, "%.0f km/h", it) } ?: "—",
                        )
                    }
                }
            }

            SettingsGroup("Kalibrace v okolí") {
                if (state.nearbyCalibrations.isEmpty()) {
                    Text(
                        text = if (state.fix == null) {
                            "Bez polohy nelze zjistit."
                        } else {
                            "Pro tuto oblast zatím žádná kalibrace není."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Uloženo ${state.nearbyCalibrations.size} kalibrací pro tuto pozici:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.nearbyCalibrations.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SettingsGroup("Vrstvy") {
                Text("Offline (v telefonu)", style = MaterialTheme.typography.labelLarge)
                if (state.offlineLayers.isEmpty()) {
                    Text(
                        text = "Žádná offline vrstva — bez signálu neuvidíš nic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    state.offlineLayers.forEach { layer ->
                        Text(
                            text = if (layer.available) {
                                "• ${layer.title}"
                            } else {
                                "• ${layer.title} — chybí: ${layer.problem ?: "soubor"}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (layer.available) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                Text(
                    text = "Jen online",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (state.onlineLayers.isEmpty()) {
                    Text("—", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.onlineLayers.forEach {
                        Text("• ${it.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SettingsGroup("Telefon") {
                InfoRow(
                    "Volné místo pro vrstvy",
                    state.freeStorageBytes?.let { formatBytes(it) } ?: "—",
                )
                SelectionContainer {
                    Text(
                        text = state.storagePath.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InfoRow("Baterie", state.batteryPercent?.let { "$it %" } ?: "—")
                val battery = state.batteryPercent
                if (battery != null && battery < 50) {
                    Text(
                        text = "Na celodenní záznam trasy je pod 50 % málo — vezmi powerbanku.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
