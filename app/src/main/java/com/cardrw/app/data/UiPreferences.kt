package com.cardrw.app.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _expertMode = MutableStateFlow(prefs.getBoolean(KEY_EXPERT, false))
    val expertMode: StateFlow<Boolean> = _expertMode.asStateFlow()

    private val _disclaimerAccepted = MutableStateFlow(prefs.getBoolean(KEY_DISCLAIMER, false))
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()

    fun setExpertMode(value: Boolean) {
        prefs.edit().putBoolean(KEY_EXPERT, value).apply()
        _expertMode.value = value
    }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
        _disclaimerAccepted.value = true
    }

    companion object {
        private const val PREFS_NAME = "cardrw_ui"
        private const val KEY_EXPERT = "expert_mode"
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
    }
}
