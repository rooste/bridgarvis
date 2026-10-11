package com.bridgarvis.message

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * ULID: 48-bit millisecond timestamp + 80 bits, Crockford base32, 26 characters.
 * Sorts by time as a plain string, which makes it a good DynamoDB sort key.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun random(time: Instant = Instant.now()): String =
        encode(time.toEpochMilli(), ByteArray(10).also { random.nextBytes(it) })

    /** Same input → same id. Used for mail so a retried notification overwrites instead of duplicating. */
    fun deterministic(time: Instant, seed: String): String =
        encode(time.toEpochMilli(), MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).copyOf(10))

    private fun encode(millis: Long, entropy: ByteArray): String {
        val chars = CharArray(26)
        var t = millis
        for (i in 9 downTo 0) {
            chars[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        // 80 bits of entropy → 16 base32 characters
        var buffer = 0
        var bits = 0
        var out = 10
        for (b in entropy) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                chars[out++] = ALPHABET[(buffer ushr (bits - 5)) and 31]
                bits -= 5
            }
        }
        return String(chars)
    }
}
