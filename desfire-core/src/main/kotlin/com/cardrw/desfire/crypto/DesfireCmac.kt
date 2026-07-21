package com.cardrw.desfire.crypto

/**
 * CMAC AES-128 (NIST SP 800-38B) tel qu’utilisé par DESFire après AuthenticateAES
 * (schéma « new » / SM EV1 AES) — cf. libfreefare `cmac` / `cmac_generate_subkeys`.
 *
 * L’IV de session est **stateful** : chaque CMAC enchaîne sur l’IV précédent.
 */
class DesfireCmac(
    private val key: ByteArray,
) {
    private val sk1: ByteArray
    private val sk2: ByteArray

    init {
        require(key.size == AesConstants.KEY_SIZE_BYTES)
        val (s1, s2) = generateSubkeys(key)
        sk1 = s1
        sk2 = s2
    }

    /**
     * Calcule le CMAC de [data] en partant de [iv] (modifié in-place → dernier bloc).
     * @return 16 octets de CMAC (DESFire n’en utilise souvent que 8)
     */
    fun compute(iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == AesCbc.BLOCK)
        val kbs = AesCbc.BLOCK
        val paddedLen = if (data.isEmpty() || data.size % kbs != 0) {
            ((data.size / kbs) + 1) * kbs
        } else {
            data.size
        }
        val buffer = ByteArray(paddedLen)
        data.copyInto(buffer)

        if (data.isEmpty() || data.size % kbs != 0) {
            // padding ISO/IEC 9797-1 method 2 : 0x80 puis 00…
            val padAt = data.size
            buffer[padAt] = 0x80.toByte()
            // reste déjà 0
            xorBlock(sk2, buffer, paddedLen - kbs)
        } else {
            xorBlock(sk1, buffer, paddedLen - kbs)
        }

        AesCbc.cbcSend(key, iv, buffer)
        // Après cbcSend, iv = dernier ciphertext = CMAC
        return iv.copyOf()
    }

    companion object {
        private const val RB_AES: Int = 0x87

        fun generateSubkeys(key: ByteArray): Pair<ByteArray, ByteArray> {
            val l = ByteArray(AesCbc.BLOCK)
            val iv = AesCbc.zeroIv()
            // Encrypt zero block → L
            val zero = ByteArray(AesCbc.BLOCK)
            zero.copyInto(l)
            AesCbc.cbcSend(key, iv, l)

            val sk1 = l.copyOf()
            val msb1 = (sk1[0].toInt() and 0x80) != 0
            shiftLeftOne(sk1)
            if (msb1) sk1[sk1.size - 1] = (sk1[sk1.size - 1].toInt() xor RB_AES).toByte()

            val sk2 = sk1.copyOf()
            val msb2 = (sk2[0].toInt() and 0x80) != 0
            shiftLeftOne(sk2)
            if (msb2) sk2[sk2.size - 1] = (sk2[sk2.size - 1].toInt() xor RB_AES).toByte()

            return sk1 to sk2
        }

        private fun shiftLeftOne(block: ByteArray) {
            var carry = 0
            for (i in block.indices.reversed()) {
                val v = block[i].toInt() and 0xFF
                block[i] = ((v shl 1) or carry).toByte()
                carry = (v ushr 7) and 1
            }
        }

        private fun xorBlock(mask: ByteArray, buffer: ByteArray, offset: Int) {
            for (i in mask.indices) {
                buffer[offset + i] = (buffer[offset + i].toInt() xor mask[i].toInt()).toByte()
            }
        }
    }
}
