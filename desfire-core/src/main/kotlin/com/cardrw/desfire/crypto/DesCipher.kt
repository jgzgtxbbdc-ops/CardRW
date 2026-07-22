package com.cardrw.desfire.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.DESedeKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * DES / 2KTDEA pour AuthenticateDES (0x0A) — mode chaîné freefare (CBC-like 8 o).
 *
 * Clé usine PICC blank : [DesConstants.FACTORY_2KTDEA_KEY] (16×0x00).
 */
object DesCipher {
    const val BLOCK = DesConstants.BLOCK_SIZE

    /** Single-DES ECB one block. */
    fun desEncryptBlock(key8: ByteArray, block: ByteArray): ByteArray {
        require(key8.size == 8 && block.size == BLOCK)
        val key = SecretKeyFactory.getInstance("DES")
            .generateSecret(DESKeySpec(key8))
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    fun desDecryptBlock(key8: ByteArray, block: ByteArray): ByteArray {
        require(key8.size == 8 && block.size == BLOCK)
        val key = SecretKeyFactory.getInstance("DES")
            .generateSecret(DESKeySpec(key8))
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    /**
     * 2KTDEA (EDE) un bloc : key16 = K1‖K2 ; Java DESede attend 24 o → K1‖K2‖K1.
     */
    fun tdea2EncryptBlock(key16: ByteArray, block: ByteArray): ByteArray {
        require(key16.size == 16 && block.size == BLOCK)
        val key24 = key16 + key16.copyOf(8)
        val key = SecretKeyFactory.getInstance("DESede")
            .generateSecret(DESedeKeySpec(key24))
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    fun tdea2DecryptBlock(key16: ByteArray, block: ByteArray): ByteArray {
        require(key16.size == 16 && block.size == BLOCK)
        val key24 = key16 + key16.copyOf(8)
        val key = SecretKeyFactory.getInstance("DESede")
            .generateSecret(DESedeKeySpec(key24))
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray = when (key.size) {
        8 -> desEncryptBlock(key, block)
        16 -> tdea2EncryptBlock(key, block)
        else -> error("Clé DES/2KTDEA : 8 ou 16 o, got ${key.size}")
    }

    fun decryptBlock(key: ByteArray, block: ByteArray): ByteArray = when (key.size) {
        8 -> desDecryptBlock(key, block)
        16 -> tdea2DecryptBlock(key, block)
        else -> error("Clé DES/2KTDEA : 8 ou 16 o, got ${key.size}")
    }

    /**
     * SEND (legacy DESFire) : ciphertext = ENC(plaintext ⊕ IV) ; IV ← ciphertext.
     * Aligné freefare `mifare_cypher_blocks_chained` MCD_SEND / MCO_ENCYPHER.
     */
    fun cbcSend(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == BLOCK && data.size % BLOCK == 0)
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
     * RECV : plaintext = DEC(ciphertext) ⊕ IV ; IV ← ciphertext.
     */
    fun cbcReceive(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == BLOCK && data.size % BLOCK == 0)
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
