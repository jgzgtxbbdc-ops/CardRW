package com.cardrw.desfire.crypto

/**
 * Constantes AES + niveaux de secure messaging (CDC §5.2).
 * Session runtime : [com.cardrw.desfire.session.Ev1Session] / [com.cardrw.desfire.session.AuthSession].
 */
object AesConstants {
    const val KEY_SIZE_BYTES: Int = 16
    const val BLOCK_SIZE_BYTES: Int = 16

    /**
     * Clé AES usine labo (16×`0x00`) — apps / PICC **déjà passés en AES**.
     *
     * Chaque accès renvoie une **nouvelle** instance pour éviter le partage
     * d’un [ByteArray] mutable (mutation accidentelle d’un singleton partagé).
     */
    val FACTORY_KEY: ByteArray
        get() = ByteArray(KEY_SIZE_BYTES)
}

/**
 * Clés **DES / 2KTDEA** usine (PICC master carte vierge NXP).
 * Les DESFire neuves d’usine ont la PICC Master Key en DES, pas en AES :
 * [AuthenticateAES] échoue avec 0xAE — c’est normal.
 */
object DesConstants {
    const val DES_KEY_SIZE: Int = 8
    const val TDEA2_KEY_SIZE: Int = 16
    const val BLOCK_SIZE: Int = 8

    /** 8×0x00 — DES simple usine. */
    val FACTORY_DES_KEY: ByteArray
        get() = ByteArray(DES_KEY_SIZE)

    /** 16×0x00 — 2KTDEA usine (K1=K2=0), cas le plus courant PICC blank. */
    val FACTORY_2KTDEA_KEY: ByteArray
        get() = ByteArray(TDEA2_KEY_SIZE)
}

enum class SecureMessagingLevel {
    NONE,
    /** AuthenticateDES/2KTDEA (0x0A) — SM legacy (carte vierge usine). */
    DES_LEGACY,
    /** AuthenticateAES + SM EV1 (CMAC / CRC32 / AES session). */
    EV1,
    /** AuthenticateEV2* + SM EV2 (v1). */
    EV2,
    ;

    val badgeLabel: String
        get() = when (this) {
            NONE -> "Sans auth"
            DES_LEGACY -> "Auth DES · legacy"
            EV1 -> "Auth AES · SM EV1"
            EV2 -> "Auth AES · SM EV2"
        }
}
