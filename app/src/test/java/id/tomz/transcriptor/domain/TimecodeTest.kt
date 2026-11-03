package id.tomz.transcriptor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimecodeTest {
    @Test
    fun `formats hours minutes seconds and milliseconds with expanded hours`() {
        assertEquals("01:02:03.004", formatTimestamp(3_723_004))
        assertEquals("123:00:00.000", formatTimestamp(442_800_000))
    }

    @Test
    fun `parses displayed timestamp without losing milliseconds`() {
        assertEquals(3_723_004L, parseTimestamp("01:02:03.004"))
        assertEquals(442_800_000L, parseTimestamp("123:00:00.000"))
    }

    @Test
    fun `rejects malformed or overflowing timestamps`() {
        listOf(
            "1:02:03.004",
            "01:60:03.004",
            "01:02:60.004",
            "01:02:03.04",
            "01:02:03,004",
            "999999999999999999999:00:00.000",
        ).forEach { assertNull(parseTimestamp(it)) }
    }
}
