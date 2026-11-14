package com.drdisagree.teledrive.presentation.preview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class PlayerSession {
    var speed by mutableFloatStateOf(1f)
    var resizeMode by mutableStateOf(PlayerResizeMode.FIT)
    var seekStepSeconds by mutableIntStateOf(10)
    var shuffle by mutableStateOf(false)
    var repeatAll by mutableStateOf(false)
    var sleepTimerDeadlineMillis by mutableLongStateOf(0L)
}
