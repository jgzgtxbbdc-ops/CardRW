package com.cardrw.desfire.crypto

/**
 * Effacement best-effort de buffers secrets (clés session / auth).
 *
 * Java/Kotlin ne garantit pas l’effacement mémoire face au GC / swap, mais
 * zéroter les tableaux dès qu’une session est invalidée limite la fenêtre
 * où une heap dump / bug dump expose le matériau (dette open-source).
 */
object SensitiveBytes {

    /** Remplit [arrays] de 0x00 (ignore null). */
    fun wipe(vararg arrays: ByteArray?) {
        for (a in arrays) {
            a?.fill(0)
        }
    }
}
