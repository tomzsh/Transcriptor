package id.tomz.transcriptor

import id.tomz.transcriptor.domain.ProjectState
import org.junit.Assert.*
import org.junit.Test

class ProcessingClockStoreTest {
    @Test fun repeatingTheCurrentStagePreservesItsOriginalStart() {
        var now = 1_000L
        val clock = ProcessingClockStore { now }
        clock.begin("project", ProjectState.TRANSCRIBING)
        now = 15_000L
        clock.begin("project", ProjectState.TRANSCRIBING)
        assertEquals(1_000L, clock.stages.value.getValue("project").startedAtElapsedMs)
    }

    @Test fun changingStageStartsANewElapsedInterval() {
        var now = 1_000L
        val clock = ProcessingClockStore { now }
        clock.begin("project", ProjectState.DECODING)
        now = 20_000L
        clock.begin("project", ProjectState.TRANSCRIBING)
        assertEquals(ProcessingStageClock(ProjectState.TRANSCRIBING, 20_000L), clock.stages.value["project"])
    }

    @Test fun finishingOneProjectLeavesOtherProjectClocksAvailable() {
        val clock = ProcessingClockStore { 100L }
        clock.begin("first", ProjectState.TRANSCRIBING)
        clock.begin("second", ProjectState.TRANSLATING)
        clock.clear("first")
        assertFalse(clock.stages.value.containsKey("first"))
        assertEquals(ProcessingStageClock(ProjectState.TRANSLATING, 100L), clock.stages.value["second"])
    }

    @Test fun retryAfterClearingGetsAFreshStartForTheSameStage() {
        var now = 1_000L
        val clock = ProcessingClockStore { now }
        clock.begin("project", ProjectState.TRANSCRIBING)
        clock.clear("project")
        now = 80_000L
        clock.begin("project", ProjectState.TRANSCRIBING)
        assertEquals(80_000L, clock.stages.value.getValue("project").startedAtElapsedMs)
    }
}
