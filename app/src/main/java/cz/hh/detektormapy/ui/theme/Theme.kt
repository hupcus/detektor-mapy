package cz.hh.detektormapy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The container/on-* roles are spelled out rather than left at their Material defaults: any
// role we skip falls back to Material's baseline purple, which then shows up on FABs, chips and
// the navigation bar and makes the app look like two different designs stitched together.
private val LightColors = lightColorScheme(
    primary = MossLight,
    onPrimary = Color.White,
    primaryContainer = MossContainerLight,
    onPrimaryContainer = MossOnContainerLight,
    secondary = BrassLight,
    onSecondary = Color.White,
    secondaryContainer = BrassContainerLight,
    onSecondaryContainer = BrassOnContainerLight,
    tertiary = ClayLight,
    onTertiary = Color.White,
    tertiaryContainer = ClayContainerLight,
    onTertiaryContainer = ClayOnContainerLight,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainerLowest = Color.White,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = SurfaceDark,
    inverseOnSurface = OnSurfaceDark,
    error = DangerRed,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = MossDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = MossContainerDark,
    onPrimaryContainer = MossOnContainerDark,
    secondary = BrassDark,
    onSecondary = OnPrimaryDark,
    secondaryContainer = BrassContainerDark,
    onSecondaryContainer = BrassOnContainerDark,
    tertiary = ClayDark,
    onTertiary = OnPrimaryDark,
    tertiaryContainer = ClayContainerDark,
    onTertiaryContainer = ClayOnContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainerLowest = Color.Black,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = SurfaceLight,
    inverseOnSurface = OnSurfaceLight,
    error = DangerRedLight,
    onError = Color.Black,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

@Composable
fun DetektorMapyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors

        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = DetektorTypography,
        content = content,
    )
}
