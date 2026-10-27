package it.federico.g7monitor.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

private val HEX = "0123456789ABCDEF".toCharArray()
internal val secureRandom = SecureRandom()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

/** Standard hex ("0A1B") to bytes. */
fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
    }
}

/**
 * xDrip stores the "keks" certificate parts as *nibble swapped* hex
 * (see jamorham.keks.util.Util.hexStringToByteArray). This decodes values copied
 * from xDrip's keks_p1 / keks_p2 / keks_p3 preferences exactly the same way.
 * Returns null on invalid input.
 */
fun xdripHexToBytes(input: String): ByteArray? {
    val s = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
    if (s.isEmpty() || s.length % 2 != 0) return null
    return ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2 + 1], 16) shl 4) + Character.digit(s[i * 2], 16)).toByte()
    }
}

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

internal fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
internal fun ByteBuffer.u16(): Int = (get().toInt() and 0xFF) or ((get().toInt() and 0xFF) shl 8)
internal fun ByteBuffer.u32(): Long =
    (get().toLong() and 0xFF) or ((get().toLong() and 0xFF) shl 8) or
        ((get().toLong() and 0xFF) shl 16) or ((get().toLong() and 0xFF) shl 24)
