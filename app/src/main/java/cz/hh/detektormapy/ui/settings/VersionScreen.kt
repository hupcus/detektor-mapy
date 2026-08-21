package cz.hh.detektormapy.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.BuildConfig
import cz.hh.detektormapy.net.UpdateChecker
import cz.hh.detektormapy.net.UpdateStatus

/**
 * "Verze a autor" — which version you are running, whether a newer one exists, and who wrote it.
 *
 * It is a pushed screen rather than a tab in Nastavení, and that is the point of the layout.
 * Tabs are for peer areas you move between often; this is a terminal page you open twice a year.
 * As a fifth tab it also pushed the row past the edge of the screen, so the label sat half cut
 * off — which reads as a broken window, not as "scroll me".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionScreen(navController: NavHostController) {
    val viewModel: VersionViewModel = hiltViewModel()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    val open: (String) -> Unit = { url -> openUrl(context, url) { viewModel.notify(it) } }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Verze a autor") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup("DetektorMapy") {
                Text("verze ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "sestavení ${BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Button(
                    onClick = viewModel::checkForUpdate,
                    enabled = check != UpdateCheck.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (check == UpdateCheck.Checking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Zjišťuji…")
                    } else {
                        Text("Zkontrolovat aktualizaci")
                    }
                }
                UpdateResult(check = check, onOpen = open)
            }

            SettingsGroup("Odkazy") {
                NavigationRow("Zdrojový kód a hlášení chyb", "github.com/hupcus/detektor-mapy") {
                    open(UpdateChecker.REPO_URL)
                }
                HorizontalDivider()
                NavigationRow("Všechny verze a co je v nich nového", "Changelog na GitHubu") {
                    open(RELEASES_PAGE)
                }
            }

            SettingsGroup("Autor") {
                Text("Honza Hubka", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Dělám to pro radost, ne pro peníze. Aplikace je zdarma, bez reklam " +
                        "a nic o tobě nesbírá. Kód je otevřený pod GPL-3.0 — kdokoliv si ho může " +
                        "vzít a rozvíjet dál po svém.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                NavigationRow("Web", "honzahubka.cz") { open(AUTHOR_WEB) }
                HorizontalDivider()
                NavigationRow("LinkedIn", "linkedin.com/in/honzahubka") { open(AUTHOR_LINKEDIN) }
            }
        }
    }
}

@Composable
private fun UpdateResult(check: UpdateCheck, onOpen: (String) -> Unit) {
    when (check) {
        UpdateCheck.Idle, UpdateCheck.Checking -> Unit

        is UpdateCheck.Done -> when (val status = check.status) {
            UpdateStatus.UpToDate -> Text(
                text = "Máš nejnovější verzi.",
                style = MaterialTheme.typography.bodyMedium,
            )

            UpdateStatus.Unavailable -> Text(
                text = "Nepodařilo se zjistit — zkus to znovu, až budeš mít signál.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UpdateStatus.Available -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Je k dispozici verze ${status.version}.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Aplikace se neaktualizuje sama. Novou verzi si musíš stáhnout a " +
                        "nainstalovat ručně, stejně jako tu první. O nálezy, místa ani stažené " +
                        "mapy nepřijdeš.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onOpen(status.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Otevřít stránku s novou verzí")
                }
            }
        }
    }
}

/** Opens [url] in whatever the phone uses for the web; reports instead of throwing. */
private fun openUrl(context: Context, url: String, onFailure: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { onFailure("Odkaz se nepodařilo otevřít") }
}

private const val AUTHOR_WEB = "https://honzahubka.cz"
private const val AUTHOR_LINKEDIN = "https://www.linkedin.com/in/honzahubka/"
private const val RELEASES_PAGE = "${UpdateChecker.REPO_URL}/releases"
