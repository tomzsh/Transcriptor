package id.tomz.transcriptor.inference

import org.junit.Assert.*
import org.junit.Test

class PerformancePolicyTest {
    @Test fun balancedLeavesRoomForTheInterface() {
        assertEquals(3, PerformancePolicy.choose(PerformanceMode.BALANCED, 8, 0).threads)
        assertEquals(2, PerformancePolicy.choose(PerformanceMode.BALANCED, 4, 0).threads)
        assertEquals(1, PerformancePolicy.choose(PerformanceMode.BALANCED, 1, 0).threads)
        assertEquals(64, PerformancePolicy.choose(PerformanceMode.BALANCED, 8, 0).batchTokens)
    }
    @Test fun fastIsBoundedAndHeatOverridesBothModes() {
        assertEquals(4, PerformancePolicy.choose(PerformanceMode.FAST, 16, 0).threads)
        for (mode in PerformanceMode.entries) {
            assertEquals(2, PerformancePolicy.choose(mode, 8, 2).threads)
            assertEquals(1, PerformancePolicy.choose(mode, 8, 3).threads)
            assertEquals(32, PerformancePolicy.choose(mode, 8, 3).batchTokens)
        }
    }
}
