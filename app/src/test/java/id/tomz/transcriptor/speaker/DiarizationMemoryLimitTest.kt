package id.tomz.transcriptor.speaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationMemoryLimitTest {
    @Test fun requiresTwicePcmBytesPlus128MiBOfRemainingHeap() {
        val pcmBytes = 10L * MIB
        val requiredHeap = pcmBytes * 2 + 128L * MIB

        assertTrue(canStartDiarization(pcmBytes, requiredHeap, lowMemory = false))
        assertFalse(canStartDiarization(pcmBytes, requiredHeap - 1, lowMemory = false))
    }

    @Test fun rejectsSystemLowMemoryAndByteCountOverflow() {
        assertFalse(canStartDiarization(1L, Long.MAX_VALUE, lowMemory = true))
        assertFalse(canStartDiarization(Long.MAX_VALUE, Long.MAX_VALUE, lowMemory = false))
        assertFalse(canStartDiarization(0L, Long.MAX_VALUE, lowMemory = false))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
