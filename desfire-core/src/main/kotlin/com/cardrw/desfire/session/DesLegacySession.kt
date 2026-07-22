package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.model.CommMode

/**
 * Session après AuthenticateDES / 2KTDEA (0x0A) — carte **vierge usine** PICC.
 *
 * SM legacy complet (CRC16 / crypto DES) non requis pour l’explore free-list ON
 * et le diagnostic moniteur. Les commandes PLAIN passent en clair ;
 * MACED/FULL lèvent une erreur pédagogique (ChangeKey / Write DES = dette v1).
 */
class DesLegacySession(
    override val aidHex: String,
    override val keyNumber: Int,
    /** Clé d’auth utilisée (8 ou 16 o) — copie, wipe côté client si besoin. */
    val authKey: ByteArray,
) : DesfireSecureSession {

    override val smLevel: SecureMessagingLevel = SecureMessagingLevel.DES_LEGACY
    override val authenticated: Boolean = true
    override val badgeLabel: String get() = smLevel.badgeLabel

    override fun toAuthSession(): AuthSession = AuthSession(
        aidHex = aidHex,
        keyNumber = keyNumber,
        smLevel = smLevel,
        authenticated = true,
    )

    override fun prepareCommand(
        opcode: Int,
        data: ByteArray,
        mode: CommMode,
        clearHeaderLength: Int,
    ): ByteArray = when (mode) {
        CommMode.PLAIN -> data
        CommMode.MACED, CommMode.FULL ->
            throw SecureMessagingException(
                "SM DES legacy pour $mode non implémenté — " +
                    "ChangeKey AES / Write après bascule AES (carte vierge usine = auth DES seule pour l’instant).",
            )
    }

    override fun postprocessResponse(
        responseData: ByteArray,
        sw2: Int,
        mode: CommMode,
    ): ByteArray = when (mode) {
        CommMode.PLAIN -> responseData
        CommMode.MACED, CommMode.FULL ->
            throw SecureMessagingException(
                "SM DES legacy RX $mode non implémenté.",
            )
    }
}
