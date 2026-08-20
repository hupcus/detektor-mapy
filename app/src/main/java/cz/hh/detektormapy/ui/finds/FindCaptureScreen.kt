package cz.hh.detektormapy.ui.finds

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.location.FixQuality
import cz.hh.detektormapy.ui.theme.DangerRed
import cz.hh.detektormapy.ui.theme.OkGreen
import cz.hh.detektormapy.ui.theme.WarnAmber
import java.util.concurrent.TimeUnit

/**
 * One-tap capture of a find (PLAN.md F2-2): shutter, form, save -- three interactions.
 *
 * The camera is started as soon as the screen appears so that the preview is already live by the
 * time the phone reaches the hole, but nothing in this flow depends on it: if the permission is
 * refused, the device has no camera, or CameraX fails to bind, the form opens straight away and
 * the find is saved without a photo. GPS is the only hard requirement, because a find without a
 * position is not a find -- and even then the last known fix is offered, clearly labelled.
 */
@Composable
fun FindCaptureScreen(navController: NavHostController) {
    val viewModel: FindCaptureViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var cameraGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionResolved by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var skipPhoto by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        permissionResolved = true
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.startLocation() }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission(context)) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.savedId) {
        if (state.savedId != null) navController.popBackStack()
    }

    LaunchedEffect(state.message) {
        val text = state.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    val cameraUsable = cameraGranted && cameraError == null
    val showForm = state.photoPath != null || skipPhoto || (permissionResolved && !cameraUsable)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Binding is tied to the composition, so leaving mid-capture always releases the camera.
    DisposableEffect(cameraGranted, lifecycleOwner) {
        if (!cameraGranted) {
            return@DisposableEffect onDispose { }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var bound: ProcessCameraProvider? = null
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    bound = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                }.onFailure {
                    cameraError = "Kameru se nepodařilo spustit, ulož nález bez fotky"
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { bound?.unbindAll() }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (cameraUsable) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cameraError
                            ?: "Fotoaparát není k dispozici -- nález ulož bez fotky.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            CaptureTopBar(
                state = state,
                onBack = {
                    viewModel.discardPhoto()
                    navController.popBackStack()
                },
                onRequestLocation = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (!showForm && cameraUsable) {
                ShutterRow(
                    capturing = capturing,
                    onShutter = {
                        if (capturing) return@ShutterRow
                        capturing = true
                        val file = viewModel.newPhotoFile()
                        runCatching {
                            imageCapture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        capturing = false
                                        viewModel.onPhotoCaptured(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        capturing = false
                                        viewModel.onPhotoFailed()
                                    }
                                },
                            )
                        }.onFailure {
                            capturing = false
                            viewModel.onPhotoFailed()
                        }
                    },
                    onSkipPhoto = { skipPhoto = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            AnimatedVisibility(
                visible = showForm,
                enter = slideInVertically { full -> full },
                exit = slideOutVertically { full -> full },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                CaptureForm(
                    state = state,
                    onCategory = viewModel::setCategory,
                    onTitle = viewModel::setTitle,
                    onDepth = viewModel::setDepthText,
                    onNote = viewModel::setNote,
                    onRetakePhoto = {
                        viewModel.discardPhoto()
                        skipPhoto = false
                    },
                    onSave = { viewModel.save() },
                    onCancel = {
                        viewModel.discardPhoto()
                        navController.popBackStack()
                    },
                    showRetake = state.photoPath != null && cameraUsable,
                )
            }
        }
    }
}

/** Back button plus the GPS pill: the one piece of status that decides whether saving works. */
@Composable
private fun CaptureTopBar(
    state: FindCaptureUiState,
    onBack: () -> Unit,
    onRequestLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět")
            }
        }
        GpsPill(state = state, onRequestLocation = onRequestLocation)
    }
}

/** Green = live fix, amber = only the last known position, red = nothing to save with. */
@Composable
private fun GpsPill(state: FindCaptureUiState, onRequestLocation: () -> Unit) {
    val fix = state.fix
    val color = when {
        fix == null -> DangerRed
        !state.fixIsLive -> WarnAmber
        state.fixQuality == FixQuality.POOR -> WarnAmber
        else -> OkGreen
    }
    val label = when {
        !state.locationPermissionGranted -> "Poloha není povolena -- klepni"
        fix == null -> "Čekám na GPS…"
        !state.fixIsLive -> "Poslední známá poloha (${ageLabel(fix.timestamp)})"
        else -> "GPS ${formatAccuracy(fix.accuracyM)}"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = Modifier.clickable(enabled = !state.locationPermissionGranted) {
            onRequestLocation()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The shutter, plus the always-available escape hatch to the form without a photo. */
@Composable
private fun ShutterRow(
    capturing: Boolean,
    onShutter: () -> Unit,
    onSkipPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.9f))
                .border(4.dp, Color.White, CircleShape)
                .clickable(enabled = !capturing, onClick = onShutter),
            contentAlignment = Alignment.Center,
        ) {
            if (capturing) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color.DarkGray)
            }
        }
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
            TextButton(onClick = onSkipPhoto) { Text("Bez fotky") }
        }
    }
}

/** The compact form that slides up after the shot: category, depth, note, title. */
@Composable
private fun CaptureForm(
    state: FindCaptureUiState,
    onCategory: (FindCategory) -> Unit,
    onTitle: (String) -> Unit,
    onDepth: (String) -> Unit,
    onNote: (String) -> Unit,
    onRetakePhoto: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    showRetake: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PhotoThumbnail(
                    uri = state.photoPath,
                    category = state.category,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentDescription = "Fotka nálezu",
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.photoPath == null) "Nález bez fotky" else "Fotka pořízena",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = state.layerTitle?.let { "Vrstva: $it" } ?: "Vrstva: neznámá",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showRetake) {
                    TextButton(onClick = onRetakePhoto) { Text("Přefotit") }
                }
            }

            if (state.fix == null) {
                Text(
                    text = "Bez GPS pozice nelze nález uložit. Vyjdi na volné prostranství " +
                        "a počkej na signál -- formulář zatím můžeš vyplnit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerRed,
                )
            } else if (!state.fixIsLive) {
                Text(
                    text = "Používá se poslední známá poloha (${ageLabel(state.fix.timestamp)}). " +
                        "Může být nepřesná.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnAmber,
                )
            }

            state.huntRankLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            CategoryPicker(selected = state.category, onSelect = onCategory)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.depthText,
                    onValueChange = onDepth,
                    label = { Text("Hloubka (cm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitle,
                    label = { Text("Název") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
            }

            OutlinedTextField(
                value = state.note,
                onValueChange = onNote,
                label = { Text("Poznámka") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.photoPath == null) "Uložit bez fotky" else "Uložit")
            }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Zrušit")
            }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** "před 3 min" -- how stale the last known position is, in words the user can act on. */
private fun ageLabel(timestamp: Long): String {
    val deltaMs = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    return when {
        minutes < 1L -> "před chvílí"
        minutes < 60L -> "před $minutes min"
        else -> "před ${TimeUnit.MILLISECONDS.toHours(deltaMs)} h"
    }
}
