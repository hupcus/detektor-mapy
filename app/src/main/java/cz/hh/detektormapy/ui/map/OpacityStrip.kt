package cz.hh.detektormapy.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.hh.detektormapy.map.LayerUiState
import kotlin.math.roundToInt

/**
 * Opacity control that lives on the map itself.
 *
 * The layer panel already has per-layer sliders, but it is a modal bottom sheet: it covers the
 * lower half of the map *and dims the rest*, so while dragging the slider you cannot see what
 * it does. Judging whether an 1840s map lines up with the ground is exactly the task that needs
 * to see the result, so the control that matters most has to be the one that does not hide it.
 *
 * Deliberately narrow and bottom-left: it leaves the find button and the right-hand controls
 * clear, and it does not dim anything.
 */
@Composable
fun OpacityStrip(
    layer: LayerUiState,
    canCycle: Boolean,
    onOpacityChange: (Float) -> Unit,
    onOpacityCommitted: () -> Unit,
    onCycleLayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (canCycle) Modifier.clickable { onCycleLayer() } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = layer.def.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canCycle) {
                    Icon(
                        imageVector = Icons.Filled.SwapVert,
                        contentDescription = "Přepnout na další vrstvu",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = layer.opacity,
                    onValueChange = onOpacityChange,
                    onValueChangeFinished = onOpacityCommitted,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(layer.opacity * 100).roundToInt()} %",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
