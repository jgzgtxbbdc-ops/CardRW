package com.cardrw.desfire.session

import com.cardrw.desfire.crypto.AesCbc
import com.cardrw.desfire.crypto.AesConstants
import com.cardrw.desfire.crypto.DesfireCmac
import com.cardrw.desfire.crypto.DesfireCrc32
import com.cardrw.desfire.crypto.SecureMessagingLevel
import com.cardrw.desfire.model.CommMode
import com.cardrw.desfire.util.Hex

/**
 * Session d’authentification AES + secure messaging EV1 (CDC §5.2 / §3.2).
 *
 * Invalidée par SelectApplication, perte de champ, échec crypto.
 */
class Ev1Session private constructor(
    override val aidHex: String,
    override val keyNumber: Int,
    val sessionKey: ByteArray,
    private val cmac: DesfireCmac,
    private val iv: ByteArray,
) : DesfireSecureSession {
    override val smLevel: SecureMessagingLevel = SecureMessagingLevel.EV1
    override val authenticated: Boolean = true

    override val badgeLabel: String get() = smLevel.badgeLabel

    override fun toAuthSession(): AuthSession = AuthSession(
        aidHex = aidHex,
        keyNumber = keyNumber,
        smLevel = smLevel,
        authenticated = true,
    )

    /**
     * Prépare le payload commande (sans le framing 90…00).
     *
     * Convention alignée freefare :
     * - buffer crypto commence par l’opcode pour CRC/CMAC ;
     * - le framing wrap envoie seulement le data field (opcode dans `90 CMD …`).
     *
     * ### Modes
     * - **PLAIN** : MAJ IV via CMAC(cmd‖data), renvoie [data] telle quelle (pas de MAC append).
     * - **MACED** : append CMAC 8 o sur cmd‖data.
     * - **FULL**  : CRC32(cmd‖data) puis AES-CBC sur le **suffixe** chiffrable uniquement
     *   (`data[clearHeaderLength..]` ‖ CRC ‖ pad). L’en-tête clair reste en tête du data field.
     *
     * ### En-têtes clairs (Write / ChangeKey v1)
     * Sur FULL, une partie des paramètres voyage en clair (carte a besoin de les lire
     * pour router / connaître la longueur) :
     * - **WriteData** `0x3D` : FileNo‖Offset‖Length → [clearHeaderLength] = 7
     * - **ChangeKey** `0xC4` : KeyNo → [clearHeaderLength] = 1
     *
     * CRC et CMAC couvrent toujours `cmd ‖ data` **entier** (en-tête + corps).
     *
     * ### ReadData FULL v0.5
     * Le TX ReadData reste en [CommMode.PLAIN] (paramètres FileNo/Offset/Length en clair
     * + CMAC IV). Le mode FULL s’applique à la **réponse** via [postprocessResponse].
     * Ne pas appeler [prepareCommand] en FULL pour ReadData.
     *
     * @param opcode code commande DESFire
     * @param data données claires complètes (en-tête + corps métier)
     * @param mode mode de communication
     * @param clearHeaderLength octets en tête de [data] laissés en clair dans le data field
     *        APDU en mode FULL (0 = chiffre tout le data). Ignoré en PLAIN / MACED.
     * @return data field APDU (MAC / ciphertext éventuels) — **sans** l’opcode
     */
    override fun prepareCommand(
        opcode: Int,
        data: ByteArray,
        mode: CommMode,
        clearHeaderLength: Int,
    ): ByteArray {
        val cmdByte = (opcode and 0xFF).toByte()
        return when (mode) {
            CommMode.PLAIN -> {
                // CMAC pour maintenir l’IV, sans append (AS_NEW plain)
                val full = byteArrayOf(cmdByte) + data
                cmac.compute(iv, full)
                data
            }
            CommMode.MACED -> {
                val full = byteArrayOf(cmdByte) + data
                val macFull = cmac.compute(iv, full)
                data + macFull.copyOf(CMAC_TX_LEN)
            }
            CommMode.FULL -> {
                require(clearHeaderLength in 0..data.size) {
                    "clearHeaderLength=$clearHeaderLength hors [0, ${data.size}]"
                }
                val header = data.copyOfRange(0, clearHeaderLength)
                val body = data.copyOfRange(clearHeaderLength, data.size)
                // CRC32(cmd‖header‖body) — en-tête inclus même s’il reste clair à l’émission
                val crc = DesfireCrc32.computeBytes(byteArrayOf(cmdByte) + data)
                val toEnc = body + crc
                val padded = padZeros(toEnc)
                AesCbc.cbcSend(sessionKey, iv, padded)
                if (header.isEmpty()) padded else header + padded
            }
        }
    }

    /**
     * Post-traite une réponse carte : [responseData] sans SW, [sw2] status DESFire.
     * @return payload utile déchiffré / sans MAC
     */
    override fun postprocessResponse(
        responseData: ByteArray,
        sw2: Int,
        mode: CommMode,
    ): ByteArray {
        val status = (sw2 and 0xFF).toByte()
        return when (mode) {
            CommMode.PLAIN -> {
                // data || CMAC(8) optionnel — si réponse pure status, data vide
                if (responseData.isEmpty()) {
                    // CMAC over status only
                    cmac.compute(iv, byteArrayOf(status))
                    ByteArray(0)
                } else if (responseData.size >= CMAC_TX_LEN) {
                    // payload || cmac8 — vérifier
                    val payload = responseData.copyOf(responseData.size - CMAC_TX_LEN)
                    val receivedMac = responseData.copyOfRange(responseData.size - CMAC_TX_LEN, responseData.size)
                    val forMac = payload + status
                    val expected = cmac.compute(iv, forMac).copyOf(CMAC_TX_LEN)
                    if (!expected.contentEquals(receivedMac)) {
                        // Certaines réponses plain sans MAC (ex. avant auth complète) :
                        // si la taille ne colle pas à un MAC, recalcul IV sur data||status.
                        // Ici on exige MAC si size >= 8 après auth — freefare le fait toujours.
                        throw SecureMessagingException(
                            "CMAC réponse invalide (plain) — attendu ${Hex.encode(expected)}, reçu ${Hex.encode(receivedMac)}",
                        )
                    }
                    payload
                } else {
                    // Pas assez pour un CMAC : MAJ IV sur data||status sans vérif stricte
                    cmac.compute(iv, responseData + status)
                    responseData
                }
            }
            CommMode.MACED -> {
                require(responseData.size >= CMAC_TX_LEN) {
                    "Réponse MAC trop courte (${responseData.size})"
                }
                val payload = responseData.copyOf(responseData.size - CMAC_TX_LEN)
                val receivedMac = responseData.copyOfRange(responseData.size - CMAC_TX_LEN, responseData.size)
                val expected = cmac.compute(iv, payload + status).copyOf(CMAC_TX_LEN)
                if (!expected.contentEquals(receivedMac)) {
                    throw SecureMessagingException(
                        "CMAC réponse invalide (mac) — attendu ${Hex.encode(expected)}, reçu ${Hex.encode(receivedMac)}",
                    )
                }
                payload
            }
            CommMode.FULL -> {
                if (responseData.isEmpty()) return ByteArray(0)
                require(responseData.size % AesCbc.BLOCK == 0) {
                    "Ciphertext FULL non aligné (${responseData.size})"
                }
                val dec = responseData.copyOf()
                AesCbc.cbcReceive(sessionKey, iv, dec)
                // Cherche CRC32(payload || status) == 0 dans le flux (comme freefare)
                extractFullPlaintext(dec, status)
            }
        }
    }

    companion object {
        const val CMAC_TX_LEN: Int = 8

        fun deriveSessionKey(rndA: ByteArray, rndB: ByteArray): ByteArray {
            require(rndA.size == 16 && rndB.size == 16)
            // RndA[0..3] || RndB[0..3] || RndA[12..15] || RndB[12..15]
            return byteArrayOf(
                rndA[0], rndA[1], rndA[2], rndA[3],
                rndB[0], rndB[1], rndB[2], rndB[3],
                rndA[12], rndA[13], rndA[14], rndA[15],
                rndB[12], rndB[13], rndB[14], rndB[15],
            )
        }

        fun create(aidHex: String, keyNumber: Int, rndA: ByteArray, rndB: ByteArray): Ev1Session {
            val sk = deriveSessionKey(rndA, rndB)
            return Ev1Session(
                aidHex = aidHex,
                keyNumber = keyNumber,
                sessionKey = sk,
                cmac = DesfireCmac(sk),
                iv = AesCbc.zeroIv(),
            )
        }

        private fun padZeros(data: ByteArray): ByteArray {
            val rem = data.size % AesCbc.BLOCK
            if (rem == 0 && data.isNotEmpty()) return data.copyOf()
            val pad = if (data.isEmpty()) AesCbc.BLOCK else AesCbc.BLOCK - rem
            return data + ByteArray(pad)
        }

        /**
         * Extrait le plaintext d’un bloc FULL déchiffré (payload + CRC32(payload||status) + padding).
         */
        private fun extractFullPlaintext(decrypted: ByteArray, status: ByteArray): ByteArray {
            // decrypted = payload || crc0..3 || padding (0x00 / éventuellement 0x80 en padding DESFire zero-pad)
            // CRC couvre payload || status — on sonde la position du CRC.
            val n = decrypted.size
            // Essai freefare : crc_pos part bas et avance
            var crcPos = (n - 16 - 3).coerceAtLeast(0)
            while (crcPos + 4 <= n) {
                val candidatePayload = decrypted.copyOf(crcPos)
                val withStatus = candidatePayload + status
                val expectedCrc = DesfireCrc32.computeBytes(withStatus)
                val actualCrc = decrypted.copyOfRange(crcPos, crcPos + 4)
                if (expectedCrc.contentEquals(actualCrc)) {
                    // padding doit être 0x00 (DESFire zero pad) après CRC
                    var padOk = true
                    for (i in crcPos + 4 until n) {
                        if (decrypted[i] != 0.toByte()) {
                            padOk = false
                            break
                        }
                    }
                    if (padOk) return candidatePayload
                }
                crcPos++
            }
            throw SecureMessagingException("CRC32 FULL réponse non vérifié (${Hex.encode(decrypted)})")
        }

        private fun extractFullPlaintext(decrypted: ByteArray, status: Byte): ByteArray =
            extractFullPlaintext(decrypted, byteArrayOf(status))
    }
}

class SecureMessagingException(message: String) : Exception(message)

/** Vue UI de session (sans clé). */
data class AuthSession(
    val aidHex: String,
    val keyNumber: Int,
    val smLevel: SecureMessagingLevel,
    val authenticated: Boolean,
) {
    val badgeLabel: String
        get() = if (authenticated) smLevel.badgeLabel else SecureMessagingLevel.NONE.badgeLabel
}
