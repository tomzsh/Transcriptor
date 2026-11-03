package id.tomz.transcriptor.speaker

import id.tomz.transcriptor.domain.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAlignerTest {
    @Test fun assignsClusterWithMostOverlappingDuration() {
        val turns = listOf(SpeakerTurn(0, 800, 7), SpeakerTurn(1_000, 2_000, 7), SpeakerTurn(0, 1_500, 3))
        val result = assignSpeakers("p", listOf(segment(0, 2_000)), turns)
        assertEquals("p:speaker-1", result.single().speakerId)
        assertEquals("p:speaker-2", assignSpeakers("p", listOf(segment(0, 1_500)), turns).single().speakerId)
    }

    @Test fun breaksTiesByFirstSpeakerAppearanceAndLeavesUnmatchedSegmentsUnassigned() {
        val turns = listOf(SpeakerTurn(1_000, 2_000, 4), SpeakerTurn(0, 1_000, 9))
        val result = assignSpeakers("p", listOf(segment(0, 2_000), segment(3_000, 4_000)), turns)
        assertEquals("p:speaker-1", result[0].speakerId)
        assertEquals(null, result[1].speakerId)
    }

    @Test fun speakerIdsDoNotDependOnDetectorResultOrderingOrOtherProjects() {
        val segments = listOf(segment(0, 2_000))
        val chronological = listOf(SpeakerTurn(0, 800, 8), SpeakerTurn(900, 1_800, 2))
        val shuffled = chronological.reversed()
        assertEquals(assignSpeakers("p", segments, chronological), assignSpeakers("p", segments, shuffled))
        assertEquals("q:speaker-2", assignSpeakers("q", segments.map { it.copy(projectId = "q") }, chronological).single().speakerId)
    }

    @Test fun speakerEntityOrderMatchesAssignmentWhenFirstTurnStartsTie() {
        val turns = listOf(SpeakerTurn(0, 1_000, 4), SpeakerTurn(0, 500, 9))

        assertEquals(listOf(9, 4), orderedSpeakerClusterIds(turns))
        assertEquals("p:speaker-1", assignSpeakers("p", listOf(segment(0, 500)), turns).single().speakerId)
    }

    private fun segment(start: Long, end: Long) = TranscriptSegment("s", "p", 0, start, end, "words", null)
}
