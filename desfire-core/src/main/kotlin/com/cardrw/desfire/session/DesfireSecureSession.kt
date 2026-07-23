package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.model.CommMode

/**
 * Session authentifiée + secure messaging (EV1 ou EV2).
 * Abstraction pour que [com.cardrw.desfire.client.DesfireClient] reste agnostique du niveau SM.
 */
interface DesfireSecureSession {
    val aidHex: String
    val keyNumber: Int
    val smLevel: SecureMessagingLevel
    val authenticated: Boolean
    val badgeLabel: String

    fun toAuthSession(): AuthSession

    fun prepareCommand(
        opcode: Int,
        data: ByteArray,
        mode: CommMode,
        clearHeaderLength: Int = 0,
    ): ByteArray

    fun postprocessResponse(
        responseData: ByteArray,
        sw2: Int,
        mode: CommMode,
    ): ByteArray

    fun prepareMetaCommand(opcode: Int, data: ByteArray = ByteArray(0)): ByteArray =
        prepareCommand(opcode, data, CommMode.PLAIN)

    fun postprocessMetaResponse(responseData: ByteArray, sw2: Int): ByteArray =
        postprocessResponse(responseData, sw2, CommMode.PLAIN)

    /**
     * Zérote les clés / IV de session en mémoire (Select, re-auth, close).
     * Après appel, ne plus utiliser la session.
     */
    fun wipeSecrets()
}
