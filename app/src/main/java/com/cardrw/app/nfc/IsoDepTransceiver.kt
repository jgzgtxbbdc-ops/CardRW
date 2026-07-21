package com.cardrw.app.nfc

import android.nfc.tech.IsoDep
import com.cardrw.desfire.client.DesfireTransceiver
import com.cardrw.desfire.client.DesfireTransportException
import java.io.IOException

/**
 * Pont IsoDep Android → [DesfireTransceiver] (desfire-core pur).
 */
class IsoDepTransceiver(
    private val isoDep: IsoDep,
    timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : DesfireTransceiver {

    init {
        isoDep.timeout = timeoutMs
    }

    override fun transceive(apdu: ByteArray): ByteArray {
        return try {
            if (!isoDep.isConnected) {
                isoDep.connect()
            }
            isoDep.transceive(apdu)
        } catch (e: IOException) {
            throw DesfireTransportException("Tag lost or IO error: ${e.message}", e)
        } catch (e: SecurityException) {
            throw DesfireTransportException("NFC security error: ${e.message}", e)
        }
    }

    companion object {
        /** Ops crypto / chaining : éviter les timeouts trop courts (CDC §6.1). */
        const val DEFAULT_TIMEOUT_MS: Int = 8_000
    }
}
