package id.tomz.transcriptor.export

import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.TranscriptDocument
import id.tomz.transcriptor.domain.TranscriptProject
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranscriptSpeaker
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExporterTest {
    private val document = TranscriptDocument(
        project = TranscriptProject("p1", "Caption", "", "audio/wav", 3_602_500, "tiny", "en", false, ProjectState.COMPLETED),
        segments = listOf(
            TranscriptSegment("s1", "p1", 0, 1_250, 3_000, "Halo & <dunia> 👋", "p1:speaker-1"),
            TranscriptSegment("s2", "p1", 1, 3_600_001, 3_602_500, "Baris satu\nBaris dua", null),
        ),
        speakers = listOf(TranscriptSpeaker("p1:speaker-1", "p1", "Ayu")),
    )

    @Test fun srtUsesStableMillisecondTimestampsAndEscapesCueMarkup() {
        val bytes = ByteArrayOutputStream()
        TranscriptExporter.write(document, ExportFormat.SRT, ExportOptions(includeTimestamps = true, includeSpeakers = false), bytes)
        val actual = bytes.toString(StandardCharsets.UTF_8.name())
        assertEquals("1\n00:00:01,250 --> 00:00:03,000\nHalo &amp; &lt;dunia&gt; 👋\n\n2\n01:00:00,001 --> 01:00:02,500\nBaris satu Baris dua\n", actual)
    }

    @Test fun vttUsesHeaderDotMillisecondsAndOptionalSpeakerPrefix() {
        val bytes = ByteArrayOutputStream()
        TranscriptExporter.write(document, ExportFormat.VTT, ExportOptions(includeTimestamps = true, includeSpeakers = true), bytes)
        val actual = bytes.toString(StandardCharsets.UTF_8.name())
        assertTrue(actual.startsWith("WEBVTT\n\n1\n00:00:01.250 --> 00:00:03.000\nAyu: "))
        assertTrue(actual.contains("01:00:00.001 --> 01:00:02.500\nBaris satu Baris dua"))
    }

    @Test fun txtAndDocxReadSavedEditsAndHonorTimestampOptions() {
        val txt = ByteArrayOutputStream()
        TranscriptExporter.write(document, ExportFormat.TXT, ExportOptions(false, true), txt)
        assertEquals("Caption\n\nAyu: Halo & <dunia> 👋\nBaris satu Baris dua\n", txt.toString(StandardCharsets.UTF_8.name()))

        val docx = ByteArrayOutputStream()
        TranscriptExporter.write(document, ExportFormat.DOCX, ExportOptions(true, true), docx)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(docx.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes().toString(StandardCharsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertTrue(entries.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml")))
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder()
        val root = parser.parse(entries.getValue("word/document.xml").byteInputStream()).documentElement
        assertEquals("document", root.localName)
        assertTrue(entries.getValue("word/document.xml").contains("Halo &amp; &lt;dunia&gt; 👋"))
        assertTrue(entries.getValue("word/document.xml").contains("01:00:00.001"))
    }

    @Test fun everyDocxPackageXmlEntryIsValid() {
        val bytes = ByteArrayOutputStream().also { TranscriptExporter.write(document, ExportFormat.DOCX, ExportOptions(false, false, true), it) }
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                    val xml = zip.readBytes()
                    assertTrue(entry.name, xml.toString(StandardCharsets.UTF_8).startsWith("<?xml"))
                    parser.parse(xml.inputStream())
                }
            }
        }
    }

    @Test fun subtitleSpeakerNamesCannotInjectMarkupOrCueBoundaries() {
        val edited = document.copy(speakers = listOf(document.speakers.single().copy(name = "R&D <QA>\n\nTeam")))
        val bytes = ByteArrayOutputStream().also { TranscriptExporter.write(edited, ExportFormat.VTT, ExportOptions(true, true), it) }
        val text = bytes.toString(StandardCharsets.UTF_8.name())
        assertTrue(text.contains("R&amp;D &lt;QA&gt;  Team: Halo"))
        assertTrue(!text.contains("<QA>"))
    }

    @Test fun emptyTranscriptAndStorageFailureAreNotReportedAsSuccess() {
        val empty = document.copy(segments = emptyList())
        try {
            TranscriptExporter.write(empty, ExportFormat.SRT, ExportOptions(true, false), ByteArrayOutputStream())
            throw AssertionError("empty transcript unexpectedly exported")
        } catch (_: EmptyTranscriptException) {
        }
        val broken = object : OutputStream() { override fun write(value: Int) { throw java.io.IOException("disk full") } }
        try {
            TranscriptExporter.write(document, ExportFormat.TXT, ExportOptions(false, false), broken)
            throw AssertionError("storage error unexpectedly succeeded")
        } catch (error: java.io.IOException) {
            assertEquals("disk full", error.message)
        }
    }
}
