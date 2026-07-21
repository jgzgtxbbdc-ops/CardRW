package com.cardrw.desfire.util

/**
 * Hex helpers used for APDU display, golden vectors and wire formats.
 * Default display style: uppercase, no separator (CDC `{{uid}}` / journal).
 */
object Hex {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray, separator: String = ""): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(bytes.size * (2 + separator.length))
        for (i in bytes.indices) {
            if (i > 0 && separator.isNotEmpty()) out.append(separator)
            val v = bytes[i].toInt() and 0xFF
            out.append(HEX_CHARS[v ushr 4])
            out.append(HEX_CHARS[v and 0x0F])
        }
        return out.toString()
    }

    fun encode(byte: Byte): String = encode(byteArrayOf(byte))

    fun decode(hex: String): ByteArray {
        val clean = hex.replace(Regex("[\\s:_-]"), "")
        require(clean.length % 2 == 0) { "Hex length must be even: ${clean.length}" }
        require(clean.matches(Regex("[0-9a-fA-F]*"))) { "Invalid hex: $hex" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun pretty(bytes: ByteArray): String = encode(bytes, " ")
}
