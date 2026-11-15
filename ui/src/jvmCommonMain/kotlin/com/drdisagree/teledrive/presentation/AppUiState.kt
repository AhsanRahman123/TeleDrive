package com.drdisagree.teledrive.presentation

import com.drdisagree.teledrive.domain.model.AppTheme
import com.drdisagree.teledrive.domain.model.AccentColor
import com.drdisagree.teledrive.domain.model.AccentLevel
import com.drdisagree.teledrive.domain.model.AppLanguage

data class AppUiState(
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
    val theme: AppTheme = AppTheme.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentColor: AccentColor = AccentColor.BLUE,
    val accentLevel: AccentLevel = AccentLevel.MEDIUM,
    val compactLayout: Boolean = false,
    val locked: Boolean = false
)
