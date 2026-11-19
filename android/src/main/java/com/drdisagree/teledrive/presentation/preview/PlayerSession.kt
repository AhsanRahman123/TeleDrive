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

    // VLC-style manual scale preset: an exact picture size calculated from the stage,
    // picked from a list — the replacement for the removed pinch zoom. Session-held
    // like the resize mode; reset to Best fit on track change.
    var videoScale by mutableStateOf(VideoScalePreset.BEST_FIT)

    fun resetVideoScale() {
        videoScale = VideoScalePreset.BEST_FIT
    }
}
