package com.nuvio.app.features.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchPartyRoomCodeTest {
    @Test
    fun generatedCodesUseOnlyReadableCharacters() {
        repeat(200) {
            val code = WatchPartyRoomCode.generate()
            assertEquals(WatchPartyRoomCode.LENGTH, code.length)
            assertTrue(code.all { it in WatchPartyRoomCode.ALPHABET }, code)
        }
    }

    @Test
    fun alphabetHasNoLookAlikeCharacters() {
        assertTrue("0O1IL".none { it in WatchPartyRoomCode.ALPHABET })
    }

    @Test
    fun bytesThatWouldBiasTheCodeAreSkipped() {
        val bytes = byteArrayOf(-1, 0, 1, 2, 3, 4, 5, 6, 7)
        val code = WatchPartyRoomCode.generate { bytes }

        assertEquals("ABCDEFGH", code)
    }

    @Test
    fun normalizeAcceptsLowercaseDashesAndSpaces() {
        assertEquals("ABCD2345", WatchPartyRoomCode.normalize(" abcd-2345 "))
    }

    @Test
    fun normalizeRejectsWrongLengthOrLookAlikes() {
        assertNull(WatchPartyRoomCode.normalize("ABCD234"))
        assertNull(WatchPartyRoomCode.normalize("ABCD23450"))
        assertNull(WatchPartyRoomCode.normalize("ABCD234O"))
    }

    @Test
    fun formatSplitsCodeInHalf() {
        assertEquals("ABCD-2345", WatchPartyRoomCode.format("ABCD2345"))
    }
}
