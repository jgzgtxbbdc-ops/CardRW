package com.cardrw.desfire.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128 ECB single-block + CBC chaining DESFire (send/receive).
 *
 * Aligné sur libfreefare `mifare_cypher_single_block` :
 * - SEND  : ciphertext = AES_ENC(plaintext ⊕ IV) ; IV ← ciphertext
 * - RECV  : plaintext  = AES_DEC(ciphertext) ⊕ IV ; IV ← ciphertext
 */
object AesCbc {
    const val BLOCK = AesConstants.BLOCK_SIZE_BYTES

    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == AesConstants.KEY_SIZE_BYTES)
        require(block.size == BLOCK)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    fun decryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == AesConstants.KEY_SIZE_BYTES)
        require(block.size == BLOCK)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    /**
     * Chiffre [data] in-place (multiple de 16) en CBC send, met à jour [iv].
     * @return data chiffrée (même instance)
     */
    fun cbcSend(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == BLOCK)
        require(data.size % BLOCK == 0)
        var offset = 0
        while (offset < data.size) {
            for (i in 0 until BLOCK) {
                data[offset + i] = (data[offset + i].toInt() xor iv[i].toInt()).toByte()
            }
            val enc = encryptBlock(key, data.copyOfRange(offset, offset + BLOCK))
            enc.copyInto(data, offset)
            enc.copyInto(iv)
            offset += BLOCK
        }
        return data
    }

    /**
     * Déchiffre [data] in-place (multiple de 16) en CBC receive, met à jour [iv].
     */
    fun cbcReceive(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == BLOCK)
        require(data.size % BLOCK == 0)
        var offset = 0
        while (offset < data.size) {
            val cipherBlock = data.copyOfRange(offset, offset + BLOCK)
            val dec = decryptBlock(key, cipherBlock)
            for (i in 0 until BLOCK) {
                data[offset + i] = (dec[i].toInt() xor iv[i].toInt()).toByte()
            }
            cipherBlock.copyInto(iv)
            offset += BLOCK
        }
        return data
    }

    fun zeroIv(): ByteArray = ByteArray(BLOCK)

    fun rotateLeft(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArray(data.size)
        System.arraycopy(data, 1, out, 0, data.size - 1)
        out[data.size - 1] = data[0]
        return out
    }
}
