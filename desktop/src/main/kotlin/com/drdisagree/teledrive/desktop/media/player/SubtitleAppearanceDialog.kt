package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.subtitle_apply
import com.drdisagree.teledrive.resources.subtitle_background
import com.drdisagree.teledrive.resources.subtitle_background_opacity
import com.drdisagree.teledrive.resources.subtitle_ass_note
import com.drdisagree.teledrive.resources.subtitle_blend_note
import com.drdisagree.teledrive.resources.subtitle_color_black
import com.drdisagree.teledrive.resources.subtitle_color_blue
import com.drdisagree.teledrive.resources.subtitle_color_cyan
import com.drdisagree.teledrive.resources.subtitle_color_lime
import com.drdisagree.teledrive.resources.subtitle_color_orange
import com.drdisagree.teledrive.resources.subtitle_color_pink
import com.drdisagree.teledrive.resources.subtitle_color_red
import com.drdisagree.teledrive.resources.subtitle_color_white
import com.drdisagree.teledrive.resources.subtitle_color_yellow
import com.drdisagree.teledrive.resources.subtitle_custom
import com.drdisagree.teledrive.resources.subtitle_custom_hint
import com.drdisagree.teledrive.resources.subtitle_defaults_hint
import com.drdisagree.teledrive.resources.subtitle_drag_hint
import com.drdisagree.teledrive.resources.subtitle_edge_depressed
import com.drdisagree.teledrive.resources.subtitle_edge_none
import com.drdisagree.teledrive.resources.subtitle_edge_outline
import com.drdisagree.teledrive.resources.subtitle_edge_raised
import com.drdisagree.teledrive.resources.subtitle_edge_shadow
import com.drdisagree.teledrive.resources.subtitle_font
import com.drdisagree.teledrive.resources.subtitle_font_casual
import com.drdisagree.teledrive.resources.subtitle_font_condensed
import com.drdisagree.teledrive.resources.subtitle_font_cursive
import com.drdisagree.teledrive.resources.subtitle_font_filter
import com.drdisagree.teledrive.resources.subtitle_font_mono
import com.drdisagree.teledrive.resources.subtitle_font_sans
import com.drdisagree.teledrive.resources.subtitle_font_serif
import com.drdisagree.teledrive.resources.subtitle_outline
import com.drdisagree.teledrive.resources.subtitle_outline_color
import com.drdisagree.teledrive.resources.subtitle_outline_opacity
import com.drdisagree.teledrive.resources.subtitle_preview_note
import com.drdisagree.teledrive.resources.subtitle_reset
import com.drdisagree.teledrive.resources.subtitle_settings_note
import com.drdisagree.teledrive.resources.subtitle_settings_title
import com.drdisagree.teledrive.resources.subtitle_size_position
import com.drdisagree.teledrive.resources.subtitle_style
import com.drdisagree.teledrive.resources.subtitle_text_color
import com.drdisagree.teledrive.resources.subtitle_text_opacity
import com.drdisagree.teledrive.resources.subtitle_text_size
import com.drdisagree.teledrive.resources.subtitle_vertical_position
import com.drdisagree.teledrive.resources.subtitle_vlc_defaults
import com.drdisagree.teledrive.resources.subtitle_window_color
import com.drdisagree.teledrive.resources.subtitle_window_opacity
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

private data class SubtitleColorOption(val label: String, val value: Int)

@Composable
private fun subtitleColorOptions(): List<SubtitleColorOption> = listOf(
    SubtitleColorOption(stringResource(Res.string.subtitle_color_white), 0xFFFFFFFF.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_yellow), 0xFFFFFF00.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_cyan), 0xFF00FFFF.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_lime), 0xFF00FF00.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_orange), 0xFFFF9800.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_pink), 0xFFFF69B4.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_red), 0xFFFF0000.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_blue), 0xFF0000FF.toInt()),
    SubtitleColorOption(stringResource(Res.string.subtitle_color_black), 0xFF000000.toInt())
)

@Composable
private fun subtitleFontShortcuts(): List<Pair<String, String>> = listOf(
    "Segoe UI" to stringResource(Res.string.subtitle_font_sans),
    "Georgia" to stringResource(Res.string.subtitle_font_serif),
    "Consolas" to stringResource(Res.string.subtitle_font_mono),
    "Comic Sans MS" to stringResource(Res.string.subtitle_font_casual),
    "Segoe Script" to stringResource(Res.string.subtitle_font_cursive),
    "Arial Narrow" to stringResource(Res.string.subtitle_font_condensed)
)

@Composable
private fun subtitleEdgeOptions(): List<Pair<Int, String>> = listOf(
    EDGE_NONE to stringResource(Res.string.subtitle_edge_none),
    EDGE_OUTLINE to stringResource(Res.string.subtitle_edge_outline),
    EDGE_SHADOW to stringResource(Res.string.subtitle_edge_shadow),
    EDGE_RAISED to stringResource(Res.string.subtitle_edge_raised),
    EDGE_DEPRESSED to stringResource(Res.string.subtitle_edge_depressed)
)

/** Approximates an installed family with a built-in Compose family for previews. */
private fun previewFontFamily(family: String): FontFamily = when (family) {
    "Georgia" -> FontFamily.Serif
    "Consolas", "Courier New" -> FontFamily.Monospace
    "Segoe Script", "Comic Sans MS" -> FontFamily.Cursive
    else -> FontFamily.Default
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleAppearanceDialog(
    appearance: SubtitleAppearance,
    onDismiss: () -> Unit,
    onApply: (SubtitleAppearance) -> Unit
) {
    var draft by remember(appearance) { mutableStateOf(appearance.normalized()) }
    val current = draft.normalized()
    val applyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { applyFocusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(Res.string.subtitle_settings_title),
                    style = MaterialTheme.typography.titleLarge
                )
                TextButton(onClick = { draft = SubtitleAppearance() }) {
                    Text(stringResource(Res.string.subtitle_reset))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .width(620.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    stringResource(Res.string.subtitle_settings_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SubtitlePreview(current)
                SectionTitle(stringResource(Res.string.subtitle_style))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = current.useSystemDefaults,
                        onClick = { draft = current.copy(useSystemDefaults = true) },
                        label = { Text(stringResource(Res.string.subtitle_vlc_defaults)) }
                    )
                    FilterChip(
                        selected = !current.useSystemDefaults,
                        onClick = { draft = current.copy(useSystemDefaults = false) },
                        label = { Text(stringResource(Res.string.subtitle_custom)) }
                    )
                }
                Text(
                    stringResource(
                        if (current.useSystemDefaults) {
                            Res.string.subtitle_defaults_hint
                        } else {
                            Res.string.subtitle_custom_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionTitle(stringResource(Res.string.subtitle_size_position))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.subtitle_text_size),
                        modifier = Modifier.width(170.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = {
                            draft = current.copy(
                                textScale = (current.textScale - 0.1f).coerceAtLeast(0.5f)
                            )
                        }
                    ) { Icon(Icons.Filled.Remove, contentDescription = null) }
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
                    ) { Icon(Icons.Filled.Add, contentDescription = null) }
                    PercentField(fraction = current.textScale) {
                        draft = current.copy(textScale = it)
                    }
                }
                PercentSliderRow(
                    label = stringResource(Res.string.subtitle_vertical_position),
                    fraction = current.verticalPosition,
                    range = 0.08f..0.92f
                ) { draft = current.copy(verticalPosition = it) }
                Text(
                    stringResource(Res.string.subtitle_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!current.useSystemDefaults) {
                    SectionTitle(stringResource(Res.string.subtitle_font))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleFontShortcuts().forEach { (family, label) ->
                            FilterChip(
                                selected = current.fontFamily == family,
                                onClick = { draft = current.copy(fontFamily = family) },
                                label = { Text(label) }
                            )
                        }
                    }
                    InstalledFontPicker(current.fontFamily) { family ->
                        draft = current.copy(fontFamily = family)
                    }

                    ColorPalette(stringResource(Res.string.subtitle_text_color), current.textColor) {
                        draft = current.copy(textColor = it)
                    }
                    PercentSliderRow(
                        label = stringResource(Res.string.subtitle_text_opacity),
                        fraction = current.textOpacity,
                        range = 0f..1f
                    ) { draft = current.copy(textOpacity = it) }

                    ColorPalette(stringResource(Res.string.subtitle_background), current.backgroundColor) {
                        draft = current.copy(backgroundColor = it)
                    }
                    PercentSliderRow(
                        label = stringResource(Res.string.subtitle_background_opacity),
                        fraction = current.backgroundOpacity,
                        range = 0f..1f
                    ) { draft = current.copy(backgroundOpacity = it) }

                    ColorPalette(stringResource(Res.string.subtitle_window_color), current.windowColor) {
                        draft = current.copy(windowColor = it)
                    }
                    PercentSliderRow(
                        label = stringResource(Res.string.subtitle_window_opacity),
                        fraction = current.windowOpacity,
                        range = 0f..1f
                    ) { draft = current.copy(windowOpacity = it) }

                    SectionTitle(stringResource(Res.string.subtitle_outline))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleEdgeOptions().forEach { (edge, label) ->
                            FilterChip(
                                selected = current.edgeType == edge,
                                onClick = { draft = current.copy(edgeType = edge) },
                                label = { Text(label) }
                            )
                        }
                    }
                    ColorPalette(stringResource(Res.string.subtitle_outline_color), current.edgeColor) {
                        draft = current.copy(edgeColor = it)
                    }
                    PercentSliderRow(
                        label = stringResource(Res.string.subtitle_outline_opacity),
                        fraction = current.edgeOpacity,
                        range = 0f..1f
                    ) { draft = current.copy(edgeOpacity = it) }
                    Text(
                        stringResource(Res.string.subtitle_blend_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(Res.string.subtitle_ass_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(current) },
                modifier = Modifier.focusRequester(applyFocusRequester)
            ) {
                Text(stringResource(Res.string.subtitle_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        }
    )
}

/**
 * Live preview of the draft appearance: two sample lines (English and Bengali)
 * over black, placed at the draft's vertical position. The preview is an
 * approximation — VLC renders text subtitles with its own layout.
 */
@Composable
private fun SubtitlePreview(draft: SubtitleAppearance) {
    val effective = if (draft.useSystemDefaults) {
        SubtitleAppearance(
            backgroundOpacity = 0f,
            verticalPosition = draft.verticalPosition
        )
    } else {
        draft
    }
    val (backgroundRgb, backgroundAlpha) = compositeSubtitleBackground(
        foregroundColor = effective.backgroundColor,
        foregroundOpacity = effective.backgroundOpacity,
        backgroundColor = effective.windowColor,
        backgroundOpacity = effective.windowOpacity
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(effective.verticalPosition.coerceIn(0.08f, 0.92f)))
                PreviewLine(
                    "The quick brown fox jumps over the lazy dog",
                    effective,
                    backgroundRgb,
                    backgroundAlpha
                )
                PreviewLine(
                    "এটি একটি সাবটাইটেল প্রিভিউ লাইন",
                    effective,
                    backgroundRgb,
                    backgroundAlpha
                )
                Spacer(
                    Modifier.weight(
                        1f - effective.verticalPosition.coerceIn(0.08f, 0.92f)
                    )
                )
            }
        }
        Text(
            stringResource(Res.string.subtitle_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreviewLine(
    text: String,
    appearance: SubtitleAppearance,
    backgroundRgb: Int,
    backgroundAlpha: Int
) {
    val shadow = if (appearance.edgeType == EDGE_NONE) {
        null
    } else {
        Shadow(
            color = Color(appearance.edgeColor).copy(alpha = appearance.edgeOpacity),
            offset = Offset(1f, 1f),
            blurRadius = 2f
        )
    }
    Text(
        text = text,
        color = Color(appearance.textColor).copy(alpha = appearance.textOpacity),
        fontSize = (15 * appearance.textScale).sp,
        fontFamily = previewFontFamily(appearance.fontFamily),
        style = LocalTextStyle.current.copy(shadow = shadow),
        maxLines = 1,
        modifier = Modifier
            .background(Color(backgroundRgb).copy(alpha = backgroundAlpha / 255f))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** Dropdown of every font family installed on this Windows machine, with a filter. */
@Composable
private fun InstalledFontPicker(selected: String, onSelected: (String) -> Unit) {
    val installedFonts = remember {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toList()
        }.getOrDefault(emptyList())
    }
    if (installedFonts.isEmpty()) return

    var menuOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(Res.string.subtitle_font_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                installedFonts
                    .filter { it.contains(filter, ignoreCase = true) }
                    .take(80)
                    .forEach { family ->
                        DropdownMenuItem(
                            text = { Text(family, fontFamily = previewFontFamily(family)) },
                            onClick = {
                                onSelected(family)
                                menuOpen = false
                            }
                        )
                    }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun PercentSliderRow(
    label: String,
    fraction: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(label, modifier = Modifier.width(170.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = fraction,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        PercentField(fraction = fraction, onCommit = onChange)
    }
}

/** Editable percentage (0–100 for opacities/position, up to 250 for text size). */
@Composable
private fun PercentField(fraction: Float, onCommit: (Float) -> Unit) {
    var text by remember(fraction) {
        mutableStateOf((fraction * 100).roundToInt().toString())
    }
    var wasFocused by remember { mutableStateOf(false) }
    fun commit() {
        text.toIntOrNull()?.let { percent -> onCommit(percent / 100f) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(3) },
        singleLine = true,
        suffix = { Text("%") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .width(92.dp)
            .onFocusChanged { state ->
                if (wasFocused && !state.isFocused) commit()
                wasFocused = state.isFocused
            }
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
            subtitleColorOptions().forEach { option ->
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
