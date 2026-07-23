package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.DesCipher
import com.cardrw.desfire.crypto.DesConstants
import com.cardrw.desfire.crypto.Iso14443aCrc16
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.crypto.SensitiveBytes
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

    override fun wipeSecrets() {
        SensitiveBytes.wipe(authKey, sessionKey)
    }

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
     * Structure (freefare / Proxmark DACd40 / NXP EV1) :
     * - KeyNo wire = `keyNo | 0x80` (type AES sur PICC master)
     * - Corps : `newKey ‖ version ‖ CRC16(newKey‖version) ‖ pad 00`
     * - Crypto SM **D40** : CBC SEND + **DECYPHER** (xor IV puis DES decrypt),
     *   IV remis à 0 — Proxmark force `xencode=false` en DACd40 pour tout envoi.
     *   (L’auth `0x0A` blank NXP utilise ENCYPHER sur la **clé usine** ; le
     *   ChangeKey chiffre avec la **session key** en mode D40 DECYPHER.)
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
        // PICC : bit 7 = AES (0x02<<6) ; 3K3DES = 0x40
        val keyNoWire = (keyNo and 0x0F) or KEYNO_AES_FLAG
        val plainBody = newAesKey + byteArrayOf((keyVersion and 0xFF).toByte())
        val crc = Iso14443aCrc16.computeBytes(plainBody)
        val toEnc = plainBody + crc
        val padded = padZerosToBlock(toEnc, DesConstants.BLOCK_SIZE)
        // SM D40 post-auth : SEND DECYPHER, IV = 0
        DesCipher.cbcSendLegacyDecrypt(sessionKey, DesCipher.zeroIv(), padded)
        return byteArrayOf(keyNoWire.toByte()) + padded
    }

    companion object {
        /** Bit type AES sur KeyNo ChangeKey (PICC) — Proxmark `0x02 << 6`. */
        const val KEYNO_AES_FLAG: Int = 0x80

        /**
         * Dérivation clé de session DESFire legacy.
         *
         * - **DES 8 o** : `RndA[0..3]‖RndB[0..3]` doublé en 16 o
         * - **2KTDEA 16 o** : `RndA[0..3]‖RndB[0..3]‖RndA[4..7]‖RndB[4..7]`
         * - Si les deux moitiés de la clé d’auth sont **identiques** (usine 00…00),
         *   le PICC traite en DES simple : **collapse** session
         *   `S[0..7]‖S[0..7]` (Proxmark après `DesfireGenSessionKeyEV1`).
         *   Sans collapse → cryptogramme ChangeKey rejeté en 0x1E.
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
                    val sk = rndA.copyOfRange(0, 4) + rndB.copyOfRange(0, 4) +
                        rndA.copyOfRange(4, 8) + rndB.copyOfRange(4, 8)
                    val k1 = authKey.copyOfRange(0, 8)
                    val k2 = authKey.copyOfRange(8, 16)
                    if (k1.contentEquals(k2)) {
                        // DES effectif (K1=K2) : session = 8 o doublé
                        val half = sk.copyOfRange(0, 8)
                        half + half.copyOf()
                    } else {
                        sk
                    }
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
