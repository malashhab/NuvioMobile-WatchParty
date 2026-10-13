package com.nuvio.app.features.watchparty

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

object WatchPartyRoomCode {
    const val LENGTH = 8

    // No 0/O or 1/I/L, so codes are easy to read aloud and type.
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    // Largest multiple of the alphabet size that fits in a byte; higher bytes are skipped to avoid bias.
    private val unbiasedByteLimit = 256 - 256 % ALPHABET.length

    fun generate(nextRandomBytes: () -> ByteArray = ::secureRandomBytes): String = buildString {
        while (length < LENGTH) {
            for (byte in nextRandomBytes()) {
                val value = byte.toInt() and 0xFF
                if (value >= unbiasedByteLimit) continue
                append(ALPHABET[value % ALPHABET.length])
                if (length == LENGTH) break
            }
        }
    }

    /** Accepts user input like "abcd-efgh" and returns "ABCDEFGH", or null if it is not a valid code. */
    fun normalize(input: String): String? =
        input
            .filterNot { it == '-' || it.isWhitespace() }
            .uppercase()
            .takeIf { code -> code.length == LENGTH && code.all { it in ALPHABET } }

    fun format(code: String): String = code.take(LENGTH / 2) + "-" + code.drop(LENGTH / 2)

    // Uuid.random() is cryptographically secure. Bytes 6 and 8 hold fixed version bits, so they are dropped.
    @OptIn(ExperimentalUuidApi::class)
    private fun secureRandomBytes(): ByteArray =
        Uuid.random().toByteArray()
            .filterIndexed { index, _ -> index != 6 && index != 8 }
            .toByteArray()
}
