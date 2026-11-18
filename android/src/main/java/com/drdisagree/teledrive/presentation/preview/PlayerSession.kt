package com.drdisagree.teledrive.presentation.preview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

const val DEFAULT_SUBTITLE_VERTICAL_POSITION = 0.88f
const val DEFAULT_SUBTITLE_HIT_WIDTH_FRACTION = 0.62f

@Stable
class PlayerSession {
    var speed by mutableFloatStateOf(1f)
    var subtitleAppearance by mutableStateOf(SubtitleAppearance())
    var resizeMode by mutableStateOf(PlayerResizeMode.FIT)
    var seekStepSeconds by mutableIntStateOf(10)
    var shuffle by mutableStateOf(false)
    var repeatAll by mutableStateOf(false)
    var sleepTimerDeadlineMillis by mutableLongStateOf(0L)

    // MX-style pinch zoom: a scale over the whole video surface (subtitles live inside
    // PlayerView, so they scale with the picture) plus the pan offset while zoomed.
    // Session-held like the resize mode; reset on track change and on resize-mode cycle.
    var zoomScale by mutableFloatStateOf(1f)
    var zoomOffsetX by mutableFloatStateOf(0f)
    var zoomOffsetY by mutableFloatStateOf(0f)

    fun resetZoom() {
        zoomScale = 1f
        zoomOffsetX = 0f
        zoomOffsetY = 0f
    }
}
