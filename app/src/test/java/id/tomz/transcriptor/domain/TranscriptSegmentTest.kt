package id.tomz.transcriptor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptSegmentTest {
    private val speakerIds = setOf("project-a:speaker-1")

    @Test fun acceptsValidTimedEdit() {
        assertNull(validateSegmentEdit(segment(1_000, 2_000, "Halo"), null, null, 10_000, speakerIds))
    }

    @Test fun rejectsBlankText() {
        assertEquals(SegmentEditError.EMPTY_TEXT, validateSegmentEdit(segment(1_000, 2_000, " \n"), null, null, 10_000, speakerIds))
    }

    @Test fun rejectsZeroLengthAndReversedTimestamps() {
        assertEquals(SegmentEditError.INVALID_RANGE, validateSegmentEdit(segment(2_000, 2_000, "x"), null, null, 10_000, speakerIds))
        assertEquals(SegmentEditError.INVALID_RANGE, validateSegmentEdit(segment(3_000, 2_000, "x"), null, null, 10_000, speakerIds))
    }

    @Test fun rejectsNegativeAndPastEndTimestamps() {
        assertEquals(SegmentEditError.INVALID_RANGE, validateSegmentEdit(segment(-1, 2_000, "x"), null, null, 10_000, speakerIds))
        assertEquals(SegmentEditError.OUTSIDE_MEDIA, validateSegmentEdit(segment(9_000, 10_001, "x"), null, null, 10_000, speakerIds))
    }

    @Test fun rejectsOverlapWithAdjacentSegments() {
        val edit = segment(1_500, 3_000, "x")
        assertEquals(SegmentEditError.OVERLAPS_NEIGHBOR, validateSegmentEdit(edit, segment(0, 1_600, "previous"), segment(3_100, 4_000, "next"), 10_000, speakerIds))
        assertEquals(SegmentEditError.OVERLAPS_NEIGHBOR, validateSegmentEdit(segment(2_500, 4_000, "x"), segment(1_000, 2_501, "previous"), null, 10_000, speakerIds))
    }

    @Test fun rejectsSpeakerFromAnotherProject() {
        assertEquals(SegmentEditError.UNKNOWN_SPEAKER, validateSegmentEdit(segment(1_000, 2_000, "x", "project-b:speaker-1"), null, null, 10_000, speakerIds))
    }

    private fun segment(start: Long, end: Long, text: String, speaker: String? = null) =
        TranscriptSegment("seg", "project-a", 0, start, end, text, speaker)
}
