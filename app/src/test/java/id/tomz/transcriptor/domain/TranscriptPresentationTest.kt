package id.tomz.transcriptor.domain

import id.tomz.transcriptor.export.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class TranscriptPresentationTest {
    private val document = TranscriptDocument(
        TranscriptProject("p", "Catatan", "audio.wav", "audio/wav", 5000, "tiny", "en", false, ProjectState.COMPLETED),
        listOf(
            TranscriptSegment("a", "p", 0, 0, 1000, "Hello there.", "s"),
            TranscriptSegment("b", "p", 1, 1000, 2500, "How are you?", "s"),
            TranscriptSegment("c", "p", 2, 2500, 5000, "Very well.", "t"),
        ),
        listOf(TranscriptSpeaker("s", "p", "Ana"), TranscriptSpeaker("t", "p", "Ben")),
    )

    @Test fun paragraphHidesAllCueMetadataWithoutChangingCues() {
        assertEquals("Hello there. How are you? Very well.", document.presentationText(TranscriptPresentation()))
        assertEquals(3, document.segments.size)
        assertEquals(1000L, document.segments[1].startMs)
    }

    @Test fun paragraphSpeakerBreaksOnlyAtSpeakerChange() {
        assertEquals("Ana: Hello there. How are you?\n\nBen: Very well.",
            document.presentationText(TranscriptPresentation(showSpeakers = true)))
    }

    @Test fun segmentMetadataIsIndependentlyConfigurable() {
        val text = document.presentationText(TranscriptPresentation(paragraphMode = false, showTimestamps = true))
        assertTrue(text.contains("00:00:01.000 – 00:00:02.500"))
        assertFalse(text.contains("Ana:"))
        assertEquals(3, text.lines().size)
    }

    @Test fun captionExportCannotSilentlyIgnoreHiddenTiming() {
        listOf(ExportFormat.SRT, ExportFormat.VTT).forEach { format ->
            assertTrue(runCatching { TranscriptExporter.write(document, format, ExportOptions(false, false), ByteArrayOutputStream()) }.isFailure)
        }
    }

    @Test fun plainTextAndWordUseSameParagraphWithoutHiddenMetadata() {
        val options = ExportOptions(false, false, paragraphMode = true)
        val txt = ByteArrayOutputStream().also { TranscriptExporter.write(document, ExportFormat.TXT, options, it) }.toString("UTF-8")
        assertEquals("Catatan\n\nHello there. How are you? Very well.\n", txt)
        val bytes = ByteArrayOutputStream().also { TranscriptExporter.write(document, ExportFormat.DOCX, options, it) }.toByteArray()
        val zip = ZipInputStream(bytes.inputStream())
        var xml = ""
        zip.use { while (true) { val entry = it.nextEntry ?: break; if (entry.name == "word/document.xml") xml = it.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(xml.contains("Hello there. How are you? Very well."))
        assertFalse(xml.contains("Ana:"))
        assertFalse(xml.contains("00:00:"))
    }
}
