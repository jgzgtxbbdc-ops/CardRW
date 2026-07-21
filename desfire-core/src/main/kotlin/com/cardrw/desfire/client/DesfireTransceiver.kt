package com.cardrw.desfire.client

/**
 * Abstraction transport APDU — implémentée côté app via [android.nfc.tech.IsoDep].
 * Permet de tester `desfire-core` sans Android.
 */
fun interface DesfireTransceiver {
    /**
     * Envoie une APDU complète et renvoie la réponse brute.
     * @throws DesfireTransportException en cas d’erreur de bas niveau (tag lost, IO…)
     */
    fun transceive(apdu: ByteArray): ByteArray
}

class DesfireTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
