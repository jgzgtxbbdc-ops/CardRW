package com.cardrw.desfire.model

import com.cardrw.desfire.util.Hex

/**
 * Application Identifier DESFire — 3 octets.
 * PICC master level : 00 00 00.
 */
@JvmInline
value class Aid(val bytes: ByteArray) {
    init {
        require(bytes.size == 3) { "AID must be 3 bytes, got ${bytes.size}" }
    }

    val hex: String get() = Hex.encode(bytes)
    val isPicc: Boolean get() = bytes.all { it == 0.toByte() }

    override fun toString(): String = hex

    companion object {
        val PICC: Aid = Aid(byteArrayOf(0x00, 0x00, 0x00))

        fun fromHex(hex: String): Aid = Aid(Hex.decode(hex))

        fun fromBytes(b0: Int, b1: Int, b2: Int): Aid =
            Aid(byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte()))

        /** Parse une liste d’AID concaténés (réponse GetApplicationIDs). */
        fun parseList(data: ByteArray): List<Aid> {
            require(data.size % 3 == 0) {
                "GetApplicationIDs data length ${data.size} not multiple of 3"
            }
            return (0 until data.size / 3).map { i ->
                Aid(data.copyOfRange(i * 3, i * 3 + 3))
            }
        }
    }
}
