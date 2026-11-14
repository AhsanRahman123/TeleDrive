package com.drdisagree.teledrive.presentation.preview

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private data class SubtitleColorOption(val label: String, val value: Int)

private val subtitleColors = listOf(
    SubtitleColorOption("White", AndroidColor.WHITE),
    SubtitleColorOption("Yellow", AndroidColor.YELLOW),
    SubtitleColorOption("Cyan", AndroidColor.CYAN),
    SubtitleColorOption("Lime", AndroidColor.GREEN),
    SubtitleColorOption("Orange", AndroidColor.rgb(255, 152, 0)),
    SubtitleColorOption("Pink", AndroidColor.rgb(255, 105, 180)),
    SubtitleColorOption("Red", AndroidColor.RED),
    SubtitleColorOption("Blue", AndroidColor.BLUE),
    SubtitleColorOption("Black", AndroidColor.BLACK)
)

private val subtitleFonts = listOf(
    "sans-serif" to "Sans",
    "serif" to "Serif",
    "monospace" to "Mono",
    "casual" to "Casual",
    "cursive" to "Cursive",
    "sans-serif-condensed" to "Condensed"
)

private val subtitleEdges = listOf(
    0 to "None",
    1 to "Outline",
    2 to "Shadow",
    3 to "Raised",
    4 to "Depressed"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleSettingsSheet(
    appearance: SubtitleAppearance,
    onAppearanceChange: (SubtitleAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    val current = appearance.normalized()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtitle settings", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onAppearanceChange(SubtitleAppearance()) }) {
                    Text("Reset")
                }
            }

            Text("Style", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.useSystemDefaults,
                    onClick = { onAppearanceChange(current.copy(useSystemDefaults = true)) },
                    label = { Text("Android defaults") }
                )
                FilterChip(
                    selected = !current.useSystemDefaults,
                    onClick = { onAppearanceChange(current.copy(useSystemDefaults = false)) },
                    label = { Text("Custom") }
                )
            }
            Text(
                if (current.useSystemDefaults) {
                    "Uses your Android caption font, colors, background and edge settings."
                } else {
                    "Customize the subtitle appearance inside TeleDrive."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Size and position")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", modifier = Modifier.weight(1f))
                Text("${(current.textScale * 100).roundToInt()}%")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        onAppearanceChange(current.copy(textScale = (current.textScale - 0.1f).coerceAtLeast(0.5f)))
                    }
                ) { Icon(Icons.Filled.Remove, contentDescription = "Zoom subtitle out") }
                Slider(
                    value = current.textScale,
                    onValueChange = { onAppearanceChange(current.copy(textScale = it)) },
                    valueRange = 0.5f..2.5f,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        onAppearanceChange(current.copy(textScale = (current.textScale + 0.1f).coerceAtMost(2.5f)))
                    }
                ) { Icon(Icons.Filled.Add, contentDescription = "Zoom subtitle in") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vertical position", modifier = Modifier.weight(1f))
                Text("${(current.verticalPosition * 100).roundToInt()}%")
            }
            Slider(
                value = current.verticalPosition,
                onValueChange = { onAppearanceChange(current.copy(verticalPosition = it)) },
                valueRange = 0.08f..0.92f
            )
            Text(
                "You can also drag the subtitle directly on the video.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!current.useSystemDefaults) {
                SectionTitle("Font")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    subtitleFonts.forEach { (family, label) ->
                        FilterChip(
                            selected = current.fontFamily == family,
                            onClick = { onAppearanceChange(current.copy(fontFamily = family)) },
                            label = { Text(label) }
                        )
                    }
                }

                ColorPalette(
                    title = "Text color",
                    selected = current.textColor,
                    onSelected = { onAppearanceChange(current.copy(textColor = it)) }
                )
                OpacityControl("Text opacity", current.textOpacity) {
                    onAppearanceChange(current.copy(textOpacity = it))
                }

                ColorPalette(
                    title = "Text background",
                    selected = current.backgroundColor,
                    onSelected = { onAppearanceChange(current.copy(backgroundColor = it)) }
                )
                OpacityControl("Background opacity", current.backgroundOpacity) {
                    onAppearanceChange(current.copy(backgroundOpacity = it))
                }

                ColorPalette(
                    title = "Subtitle window color",
                    selected = current.windowColor,
                    onSelected = { onAppearanceChange(current.copy(windowColor = it)) }
                )
                OpacityControl("Window opacity", current.windowOpacity) {
                    onAppearanceChange(current.copy(windowOpacity = it))
                }

                SectionTitle("Text outline")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    subtitleEdges.forEach { (edge, label) ->
                        FilterChip(
                            selected = current.edgeType == edge,
                            onClick = { onAppearanceChange(current.copy(edgeType = edge)) },
                            label = { Text(label) }
                        )
                    }
                }
                ColorPalette(
                    title = "Outline color",
                    selected = current.edgeColor,
                    onSelected = { onAppearanceChange(current.copy(edgeColor = it)) }
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPalette(
    title: String,
    selected: Int,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subtitleColors.forEach { option ->
                FilterChip(
                    selected = selected == option.value,
                    onClick = { onSelected(option.value) },
                    label = { Text(option.label) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .background(ComposeColor(option.value), CircleShape)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun OpacityControl(
    title: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${(value * 100).roundToInt()}%")
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f)
}
