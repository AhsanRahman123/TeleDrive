package com.drdisagree.teledrive.presentation.preview

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

private data class SubtitleColorOption(val label: String, val value: Int)

private val subtitleColors = listOf(
    SubtitleColorOption("White", 0xFFFFFFFF.toInt()),
    SubtitleColorOption("Yellow", 0xFFFFFF00.toInt()),
    SubtitleColorOption("Cyan", 0xFF00FFFF.toInt()),
    SubtitleColorOption("Lime", 0xFF00FF00.toInt()),
    SubtitleColorOption("Orange", 0xFFFF9800.toInt()),
    SubtitleColorOption("Pink", 0xFFFF69B4.toInt()),
    SubtitleColorOption("Red", 0xFFFF0000.toInt()),
    SubtitleColorOption("Blue", 0xFF0000FF.toInt()),
    SubtitleColorOption("Black", 0xFF000000.toInt())
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
    SUBTITLE_EDGE_NONE to "None",
    SUBTITLE_EDGE_OUTLINE to "Outline",
    SUBTITLE_EDGE_SHADOW to "Shadow",
    SUBTITLE_EDGE_RAISED to "Raised",
    SUBTITLE_EDGE_DEPRESSED to "Depressed"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AndroidSubtitleStyleDialog(
    appearance: AndroidSubtitleStyle,
    onDismiss: () -> Unit,
    onApply: (AndroidSubtitleStyle) -> Unit
) {
    var draft by remember(appearance) { mutableStateOf(appearance.normalized()) }
    val current = draft.normalized()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtitle settings", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { draft = AndroidSubtitleStyle() }) {
                    Text("Reset")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "These controls are for the Android player and are applied through Android Media3. " +
                        "They are stored separately from Windows subtitle settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionTitle("Style")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = current.useMedia3Defaults,
                        onClick = { draft = current.copy(useMedia3Defaults = true) },
                        label = { Text("Media3 defaults") }
                    )
                    FilterChip(
                        selected = !current.useMedia3Defaults,
                        onClick = { draft = current.copy(useMedia3Defaults = false) },
                        label = { Text("Custom") }
                    )
                }

                SectionTitle("Size and position")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text size", modifier = Modifier.weight(1f))
                    Text("${(current.textScale * 100).roundToInt()}%")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            draft = current.copy(
                                textScale = (current.textScale - 0.1f).coerceAtLeast(0.5f)
                            )
                        }
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease subtitle size")
                    }
                    Slider(
                        value = current.textScale,
                        onValueChange = { draft = current.copy(textScale = it) },
                        valueRange = 0.5f..2.5f,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            draft = current.copy(
                                textScale = (current.textScale + 0.1f).coerceAtMost(2.5f)
                            )
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase subtitle size")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vertical position", modifier = Modifier.weight(1f))
                    Text("${(current.verticalPosition * 100).roundToInt()}%")
                }
                Slider(
                    value = current.verticalPosition,
                    onValueChange = { draft = current.copy(verticalPosition = it) },
                    valueRange = 0.08f..0.92f
                )

                if (!current.useMedia3Defaults) {
                    SectionTitle("Font")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleFonts.forEach { (family, label) ->
                            FilterChip(
                                selected = current.fontFamily == family,
                                onClick = { draft = current.copy(fontFamily = family) },
                                label = { Text(label) }
                            )
                        }
                    }

                    ColorPalette("Text color", current.textColor) {
                        draft = current.copy(textColor = it)
                    }
                    OpacityControl("Text opacity", current.textOpacity) {
                        draft = current.copy(textOpacity = it)
                    }

                    ColorPalette("Text background", current.backgroundColor) {
                        draft = current.copy(backgroundColor = it)
                    }
                    OpacityControl("Background opacity", current.backgroundOpacity) {
                        draft = current.copy(backgroundOpacity = it)
                    }

                    ColorPalette("Subtitle window", current.windowColor) {
                        draft = current.copy(windowColor = it)
                    }
                    OpacityControl("Window opacity", current.windowOpacity) {
                        draft = current.copy(windowOpacity = it)
                    }

                    SectionTitle("Text outline")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleEdges.forEach { (edge, label) ->
                            FilterChip(
                                selected = current.edgeType == edge,
                                onClick = { draft = current.copy(edgeType = edge) },
                                label = { Text(label) }
                            )
                        }
                    }
                    ColorPalette("Outline color", current.edgeColor) {
                        draft = current.copy(edgeColor = it)
                    }
                    OpacityControl("Outline opacity", current.edgeOpacity) {
                        draft = current.copy(edgeOpacity = it)
                    }
                    Text(
                        "TeleDrive applies this style through Android Media3 without pausing or reopening the video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(current) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
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
                                .background(Color(option.value), CircleShape)
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
