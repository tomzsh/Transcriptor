package id.tomz.transcriptor.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiarizationTimingTest {
    @Test fun convertsSecondsToRoundedMillisecondsAndPreservesCluster() {
        assertEquals(SpeakerTurn(125, 1_250, 7), speakerTurnFromSeconds(0.125f, 1.25f, 7, 5_000))
    }

    @Test fun clipsDiarizationTurnsToMediaDuration() {
        assertEquals(SpeakerTurn(2_500, 3_000, 2), speakerTurnFromSeconds(2.5f, 4.0f, 2, 3_000))
    }

    @Test fun rejectsMalformedOrEmptyTurns() {
        assertNull(speakerTurnFromSeconds(Float.NaN, 1.0f, 0, 3_000))
        assertNull(speakerTurnFromSeconds(2.0f, 1.0f, 0, 3_000))
        assertNull(speakerTurnFromSeconds(3.0f, 4.0f, 0, 3_000))
        assertNull(speakerTurnFromSeconds(0.0f, 1.0f, 0, 0))
    }
    @Test fun mapsWorkerChunkProgressToBoundedPercent() {
        assertEquals(0, diarizationProgressPercent(0, 10))
        assertEquals(50, diarizationProgressPercent(5, 10))
        assertEquals(100, diarizationProgressPercent(12, 10))
        assertEquals(0, diarizationProgressPercent(1, 0))
    }

    @Test fun nativeCallbackPreservesProcessedAndTotalChunkCounts() {
        val reportedProgress = mutableListOf<Pair<Int, Int>>()
        val callback = SherpaProgressCallback { processed, total -> reportedProgress += processed to total }
        val method = callback.javaClass.getDeclaredMethod(
            "invoke",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )

        assertEquals(Int::class.javaObjectType, method.returnType)
        assertEquals(0, method.invoke(callback, 3, 4, 0L))
        assertEquals(listOf(3 to 4), reportedProgress)
    }

}
