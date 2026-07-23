package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.DesfireCmac
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.crypto.SensitiveBytes
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.util.Hex

/**
 * Session AuthenticateEV2First + secure messaging EV2 (NXP AN12343 / datasheet EV2+).
 *
 * - Deux clés session : [encKey] (chiffrement) et [macKey] (CMAC)
 * - [ti] Transaction Identifier (4 o) renvoyé par la carte
 * - [cmdCtr] compteur 16-bit LE, part de 0, +1 après chaque couple cmd/réponse
 * - Truncation MAC EV2 : octets impairs du CMAC 16 o → 8 o
 */
class Ev2Session private constructor(
    override val aidHex: String,
    override val keyNumber: Int,
    val encKey: ByteArray,
    val macKey: ByteArray,
    val ti: ByteArray,
    private var cmdCtr: Int = 0,
) : DesfireSecureSession {

    init {
        require(encKey.size == AesConstants.KEY_SIZE_BYTES)
        require(macKey.size == AesConstants.KEY_SIZE_BYTES)
        require(ti.size == TI_LEN)
    }

    override val smLevel: SecureMessagingLevel = SecureMessagingLevel.EV2
    override val authenticated: Boolean = true
    override val badgeLabel: String get() = smLevel.badgeLabel

    val cmdCounter: Int get() = cmdCtr

    override fun toAuthSession(): AuthSession = AuthSession(
        aidHex = aidHex,
        keyNumber = keyNumber,
        smLevel = smLevel,
        authenticated = true,
    )

    override fun wipeSecrets() {
        SensitiveBytes.wipe(encKey, macKey, ti)
    }

    /**
     * Prépare le data field APDU (sans opcode).
     * PLAIN / MACED EV2 : data ‖ MAC8 (sauf data vide pure status-only côté TX rare).
     * FULL : en-tête clair + ciphertext(padding ISO9797-2) ; MAC séparé non utilisé ici
     *        (FULL EV2 = chiffrement + MAC selon mode — on fait cipher + MAC append).
     */
    override fun prepareCommand(
        opcode: Int,
        data: ByteArray,
        mode: CommMode,
        clearHeaderLength: Int,
    ): ByteArray {
        val cmd = (opcode and 0xFF).toByte()
        return when (mode) {
            CommMode.PLAIN, CommMode.MACED -> {
                // EV2 : même après « plain », un MAC est appendé (CmdCtr + TI)
                val mac = computeCommandMac(cmd, data)
                data + mac
            }
            CommMode.FULL -> {
                require(clearHeaderLength in 0..data.size)
                val header = data.copyOfRange(0, clearHeaderLength)
                val body = data.copyOfRange(clearHeaderLength, data.size)
                val padded = padIso9797Method2(body)
                val iv = encryptionIv()
                val cipher = padded.copyOf()
                AesCbc.cbcSend(encKey, iv, cipher)
                val plainForMac = header + cipher
                val mac = computeCommandMac(cmd, plainForMac)
                header + cipher + mac
            }
        }
    }

    override fun postprocessResponse(
        responseData: ByteArray,
        sw2: Int,
        mode: CommMode,
    ): ByteArray {
        val status = (sw2 and 0xFF).toByte()
        val result = when (mode) {
            CommMode.PLAIN, CommMode.MACED -> {
                if (responseData.isEmpty()) {
                    // status-only : MAC sur status
                    verifyResponseMac(status, ByteArray(0), ByteArray(0))
                    ByteArray(0)
                } else {
                    require(responseData.size >= MAC_LEN) {
                        "Réponse EV2 trop courte pour MAC (${responseData.size})"
                    }
                    val payload = responseData.copyOf(responseData.size - MAC_LEN)
                    val mac = responseData.copyOfRange(responseData.size - MAC_LEN, responseData.size)
                    verifyResponseMac(status, payload, mac)
                    payload
                }
            }
            CommMode.FULL -> {
                require(responseData.size >= MAC_LEN) {
                    "Réponse FULL EV2 trop courte (${responseData.size})"
                }
                val withMac = responseData
                val mac = withMac.copyOfRange(withMac.size - MAC_LEN, withMac.size)
                val cipher = withMac.copyOf(withMac.size - MAC_LEN)
                // Vérifier MAC sur cipher (avant decrypt) — data field chiffré
                verifyResponseMac(status, cipher, mac)
                require(cipher.size % AesCbc.BLOCK == 0) {
                    "Cipher FULL EV2 non aligné (${cipher.size})"
                }
                val iv = encryptionIv()
                val dec = cipher.copyOf()
                AesCbc.cbcReceive(encKey, iv, dec)
                unpadIso9797Method2(dec)
            }
        }
        // Compteur : +1 après chaque échange réussi
        cmdCtr = (cmdCtr + 1) and 0xFFFF
        return result
    }

    private fun computeCommandMac(cmd: ByteArray, data: ByteArray): ByteArray {
        // Input MAC cmd = Cmd || CmdCtr_LE || TI || data
        val input = byteArrayOf(cmd[0]) + cmdCtrLe() + ti + data
        val full = oneShotCmac(macKey, input)
        return truncateEv2Mac(full)
    }

    private fun computeCommandMac(cmd: Byte, data: ByteArray): ByteArray =
        computeCommandMac(byteArrayOf(cmd), data)

    private fun verifyResponseMac(status: Byte, data: ByteArray, received: ByteArray) {
        // Input MAC resp = Status || CmdCtr_LE || TI || data
        val input = byteArrayOf(status) + cmdCtrLe() + ti + data
        val expected = truncateEv2Mac(oneShotCmac(macKey, input))
        if (!expected.contentEquals(received)) {
            throw SecureMessagingException(
                "CMAC EV2 réponse invalide — attendu ${Hex.encode(expected)}, reçu ${Hex.encode(received)}",
            )
        }
    }

    private fun encryptionIv(): ByteArray {
        // IV = E(SesAuthENCKey, TI || CmdCtr_LE || 00…00)
        val block = ByteArray(AesCbc.BLOCK)
        ti.copyInto(block, 0)
        val ctr = cmdCtrLe()
        block[4] = ctr[0]
        block[5] = ctr[1]
        // rest zero
        return AesCbc.encryptBlock(encKey, block)
    }

    private fun cmdCtrLe(): ByteArray = byteArrayOf(
        (cmdCtr and 0xFF).toByte(),
        ((cmdCtr ushr 8) and 0xFF).toByte(),
    )

    companion object {
        const val MAC_LEN = 8
        const val TI_LEN = 4
        private const val RND_LEN = 16

        /**
         * Dérive SesAuthMACKey (SV1) et SesAuthENCKey (SV2) — NXP EV2.
         *
         * SV1 = A5 5A 00 01 00 80 ‖ RndA[0..1] ‖ (RndA[2..7]⊕RndB[0..5]) ‖ RndB[6..15] ‖ RndA[8..15]
         * SV2 = 5A A5 00 01 00 80 ‖ (même queue)
         */
        fun deriveSessionKeys(authKey: ByteArray, rndA: ByteArray, rndB: ByteArray): Pair<ByteArray, ByteArray> {
            require(authKey.size == AesConstants.KEY_SIZE_BYTES)
            require(rndA.size == RND_LEN && rndB.size == RND_LEN)
            val common = ByteArray(26)
            // RndA[0..1]
            common[0] = rndA[0]
            common[1] = rndA[1]
            // xor RndA[2..7] with RndB[0..5]
            for (i in 0 until 6) {
                common[2 + i] = (rndA[2 + i].toInt() xor rndB[i].toInt()).toByte()
            }
            // RndB[6..15]
            rndB.copyInto(common, 8, 6, 16)
            // RndA[8..15]
            rndA.copyInto(common, 18, 8, 16)

            val sv1 = byteArrayOf(0xA5.toByte(), 0x5A, 0x00, 0x01, 0x00, 0x80.toByte()) + common
            val sv2 = byteArrayOf(0x5A, 0xA5.toByte(), 0x00, 0x01, 0x00, 0x80.toByte()) + common
            val macKey = oneShotCmac(authKey, sv1)
            val encKey = oneShotCmac(authKey, sv2)
            return encKey to macKey
        }

        fun create(
            aidHex: String,
            keyNumber: Int,
            authKey: ByteArray,
            rndA: ByteArray,
            rndB: ByteArray,
            ti: ByteArray,
        ): Ev2Session {
            val (enc, mac) = deriveSessionKeys(authKey, rndA, rndB)
            return Ev2Session(
                aidHex = aidHex,
                keyNumber = keyNumber,
                encKey = enc,
                macKey = mac,
                ti = ti.copyOf(),
                cmdCtr = 0,
            )
        }

        /** CMAC 16 o (IV zéro) — pour dérivation SV et MAC one-shot. */
        fun oneShotCmac(key: ByteArray, data: ByteArray): ByteArray {
            val cmac = DesfireCmac(key)
            val iv = AesCbc.zeroIv()
            return cmac.compute(iv, data)
        }

        /** Truncation EV2 : octets d’indices 1,3,5,7,9,11,13,15. */
        fun truncateEv2Mac(cmac16: ByteArray): ByteArray {
            require(cmac16.size == 16)
            return byteArrayOf(
                cmac16[1], cmac16[3], cmac16[5], cmac16[7],
                cmac16[9], cmac16[11], cmac16[13], cmac16[15],
            )
        }

        fun padIso9797Method2(data: ByteArray): ByteArray {
            val rem = data.size % AesCbc.BLOCK
            val padLen = if (rem == 0) AesCbc.BLOCK else AesCbc.BLOCK - rem
            val out = data + ByteArray(padLen)
            out[data.size] = 0x80.toByte()
            return out
        }

        fun unpadIso9797Method2(data: ByteArray): ByteArray {
            var i = data.size - 1
            while (i >= 0 && data[i] == 0.toByte()) i--
            require(i >= 0 && data[i] == 0x80.toByte()) {
                "Padding ISO9797-2 invalide (${Hex.encode(data)})"
            }
            return data.copyOf(i)
        }
    }
}
