package com.cardrw.desfire.crypto

/**
 * Constantes AES + niveaux de secure messaging (CDC §5.2).
 * Session runtime : [com.cardrw.desfire.session.Ev1Session] / [com.cardrw.desfire.session.AuthSession].
 */
object AesConstants {
    const val KEY_SIZE_BYTES: Int = 16
    const val BLOCK_SIZE_BYTES: Int = 16

    /**
     * Clé usine labo (16×`0x00`) — jamais en production.
     *
     * Chaque accès renvoie une **nouvelle** instance pour éviter le partage
     * d’un [ByteArray] mutable (mutation accidentelle d’un singleton partagé).
     */
    val FACTORY_KEY: ByteArray
        get() = ByteArray(KEY_SIZE_BYTES)
}

enum class SecureMessagingLevel {
    NONE,
    /** AuthenticateAES + SM EV1 (CMAC / CRC32 / AES session). */
    EV1,
    /** AuthenticateEV2* + SM EV2 (v1). */
    EV2,
    ;

    val badgeLabel: String
        get() = when (this) {
            NONE -> "Sans auth"
            EV1 -> "Auth AES · SM EV1"
            EV2 -> "Auth AES · SM EV2"
        }
}
