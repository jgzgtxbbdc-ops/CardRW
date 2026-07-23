package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.DesCipher
import com.cardrw.desfire.crypto.DesConstants
import com.cardrw.desfire.crypto.Iso14443aCrc16
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.model.CommMode

/**
 * Session après AuthenticateDES / 2KTDEA (0x0A) — carte **vierge usine** PICC.
 *
 * SM legacy minimal : PLAIN en clair + [prepareChangeKeyDesToAes] (ChangeKey 0xC4
 * DES→AES). MACED / Write FULL DES restent hors scope (bascule AES d’abord).
 */
class DesLegacySession(
    override val aidHex: String,
    override val keyNumber: Int,
    /** Clé d’auth utilisée (8 ou 16 o) — copie. */
    val authKey: ByteArray,
    /**
     * Clé de session dérivée de RndA/RndB (8 o DES ou 16 o 2KTDEA).
     * Voir [deriveSessionKey].
     */
    val sessionKey: ByteArray,
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
                "SM DES legacy pour $mode non implémenté hors ChangeKey — " +
                    "utiliser [prepareChangeKeyDesToAes] pour basculer la master PICC en AES, " +
                    "puis Write/Create en session AES.",
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

    /**
     * ChangeKey (0xC4) DES legacy → nouvelle clé **AES-128**, cas **même slot**
     * (typiquement master PICC 0 authentifié).
     *
     * Structure (freefare / NXP EV1) :
     * - KeyNo wire = `keyNo | 0x80` (type AES sur PICC)
     * - Corps : `newKey ‖ version ‖ CRC16(newKey‖version) ‖ pad 00`
     * - Crypto : CBC **SEND ENCYPHER** (même sens que AuthenticateDES terrain NXP),
     *   IV remis à 0 — *pas* le SEND DECYPHER de libfreefare (incompatible avec
     *   les blank NXP où l’auth n’accepte que ENCYPHER).
     * - KeyNo reste **clair** en tête du data field
     *
     * @return data field APDU (sans opcode) : KeyNo ‖ cryptogramme
     */
    fun prepareChangeKeyDesToAes(
        keyNo: Int,
        newAesKey: ByteArray,
        keyVersion: Int = 0,
    ): ByteArray {
        require(keyNo in 0..13) { "keyNo hors plage 0–13: $keyNo" }
        require(newAesKey.size == 16) {
            "nouvelle clé AES 16 o, got ${newAesKey.size}"
        }
        // PICC : bit 7 = AES (freefare) ; 3K3DES utiliserait 0x40
        val keyNoWire = (keyNo and 0x0F) or KEYNO_AES_FLAG
        val plainBody = newAesKey + byteArrayOf((keyVersion and 0xFF).toByte())
        val crc = Iso14443aCrc16.computeBytes(plainBody)
        val toEnc = plainBody + crc
        val padded = padZerosToBlock(toEnc, DesConstants.BLOCK_SIZE)
        // Aligné AuthenticateDES blank NXP : SEND = ENCYPHER CBC, IV = 0
        DesCipher.cbcSendEncrypt(sessionKey, DesCipher.zeroIv(), padded)
        return byteArrayOf(keyNoWire.toByte()) + padded
    }

    companion object {
        /** Bit type AES sur KeyNo ChangeKey (PICC / master app crypto). */
        const val KEYNO_AES_FLAG: Int = 0x80

        /**
         * Dérivation clé de session DESFire legacy (libfreefare
         * `mifare_desfire_session_key_new`).
         *
         * - **DES 8 o** : `RndA[0..3]‖RndB[0..3]` puis doublé en 16 o (K1=K2)
         * - **2KTDEA 16 o** : `RndA[0..3]‖RndB[0..3]‖RndA[4..7]‖RndB[4..7]`
         */
        fun deriveSessionKey(rndA: ByteArray, rndB: ByteArray, authKey: ByteArray): ByteArray {
            require(rndA.size == 8 && rndB.size == 8) {
                "RndA/RndB DES : 8 o, got ${rndA.size}/${rndB.size}"
            }
            return when (authKey.size) {
                8 -> {
                    val half = rndA.copyOfRange(0, 4) + rndB.copyOfRange(0, 4)
                    half + half.copyOf()
                }
                16 -> {
                    rndA.copyOfRange(0, 4) + rndB.copyOfRange(0, 4) +
                        rndA.copyOfRange(4, 8) + rndB.copyOfRange(4, 8)
                }
                else -> error("Clé DES/2KTDEA : 8 ou 16 o, got ${authKey.size}")
            }
        }

        private fun padZerosToBlock(data: ByteArray, block: Int): ByteArray {
            if (data.isEmpty()) return ByteArray(block)
            if (data.size % block == 0) return data.copyOf()
            val paddedLen = ((data.size / block) + 1) * block
            return data.copyOf(paddedLen)
        }
    }
}
