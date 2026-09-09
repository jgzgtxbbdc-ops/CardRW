package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cardrw.app.data.UiPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class UiSettingsViewModel @Inject constructor(
    private val prefs: UiPreferences,
) : ViewModel() {
    val expertMode = prefs.expertMode
    val disclaimerAccepted = prefs.disclaimerAccepted

    fun setExpertMode(value: Boolean) = prefs.setExpertMode(value)

    fun acceptDisclaimer() = prefs.acceptDisclaimer()
}
