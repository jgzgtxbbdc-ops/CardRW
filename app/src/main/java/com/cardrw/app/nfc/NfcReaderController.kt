package com.cardrw.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle

/**
 * Active [NfcAdapter.enableReaderMode] — flux NFC principal (CDC §6 / §11.21).
 *
 * Doit rester actif sur l’Activity **au premier plan** (voir [com.cardrw.app.MainActivity])
 * pour éviter le chooser système « Tag NFC détecté ».
 */
class NfcReaderController(
    private val activity: Activity,
    private val onTag: (Tag) -> Unit,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val nfc = adapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        nfc.enableReaderMode(activity, { tag -> onTag(tag) }, flags, extras)
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    companion object {
        fun isIsoDep(tag: Tag): Boolean =
            tag.techList.any { it == IsoDep::class.java.name }
    }
}
