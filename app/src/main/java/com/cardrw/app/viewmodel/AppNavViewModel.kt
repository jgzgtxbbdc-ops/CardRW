package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cardrw.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Expose le bus NFC au NavHost (navigation vers Carte si besoin). */
@HiltViewModel
class AppNavViewModel @Inject constructor(
    val tagBus: NfcTagBus,
) : ViewModel()
