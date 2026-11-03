package id.tomz.transcriptor.export

import id.tomz.transcriptor.domain.TranscriptDocument
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranscriptSpeaker
import id.tomz.transcriptor.domain.formatTimestamp
import id.tomz.transcriptor.domain.TranscriptPresentation
import id.tomz.transcriptor.domain.presentationText
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat { TXT, SRT, VTT, DOCX }

data class ExportOptions(val includeTimestamps: Boolean, val includeSpeakers: Boolean, val paragraphMode: Boolean = false)

class EmptyTranscriptException : IOException("There are no transcript cues to export")

object TranscriptExporter {
    fun write(document: TranscriptDocument, format: ExportFormat, options: ExportOptions, output: OutputStream) {
        if (format == ExportFormat.SRT || format == ExportFormat.VTT) {
            require(options.includeTimestamps && !options.paragraphMode) {
                "SRT/VTT memerlukan waktu dan segmen. Gunakan TXT/DOCX untuk paragraf tanpa timestamp."
            }
        }
        val segments = document.segments.sortedWith(
            compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs }.thenBy { it.ordinal },
        )
        if (segments.isEmpty()) throw EmptyTranscriptException()
        when (format) {
            ExportFormat.TXT -> writeText(document, segments, options, output)
            ExportFormat.SRT -> writeCaptions(document, segments, options, output, vtt = false)
            ExportFormat.VTT -> writeCaptions(document, segments, options, output, vtt = true)
            ExportFormat.DOCX -> writeDocx(document, segments, options, output)
        }
    }

    private fun writeText(
        document: TranscriptDocument,
        segments: List<TranscriptSegment>,
        options: ExportOptions,
        output: OutputStream,
    ) {
        val text = document.project.title + "\n\n" +
            document.copy(segments = segments).presentationText(
                TranscriptPresentation(options.paragraphMode, options.includeTimestamps, options.includeSpeakers),
            ) + "\n"
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun writeCaptions(
        document: TranscriptDocument,
        segments: List<TranscriptSegment>,
        options: ExportOptions,
        output: OutputStream,
        vtt: Boolean,
    ) {
        val speakers = document.speakers.associateBy { it.id }
        val separator = if (vtt) '.' else ','
        val text = buildString {
            if (vtt) append("WEBVTT\n\n")
            segments.forEachIndexed { index, segment ->
                append(index + 1).append('\n')
                append(formatTimestamp(segment.startMs, separator)).append(" --> ")
                    .append(formatTimestamp(segment.endMs, separator)).append('\n')
                append(escapeCaption(flattenLines(speakerPrefix(segment, speakers, options.includeSpeakers) + segment.text)))
                append('\n')
                if (index != segments.lastIndex) append('\n')
            }
        }
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun speakerPrefix(
        segment: TranscriptSegment,
        speakers: Map<String, TranscriptSpeaker>,
        enabled: Boolean,
    ): String {
        if (!enabled) return ""
        val name = segment.speakerId?.let(speakers::get)?.name?.takeIf(String::isNotBlank) ?: return ""
        return "$name: "
    }

    private fun flattenLines(text: String): String = text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')

    private fun escapeCaption(text: String): String = buildString(text.length) {
        for (char in text) when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(char)
        }
    }


    private fun writeDocx(
        document: TranscriptDocument,
        segments: List<TranscriptSegment>,
        options: ExportOptions,
        output: OutputStream,
    ) {
        val speakers = document.speakers.associateBy { it.id }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
            append(paragraph(document.project.title))
            val body = document.copy(segments = segments).presentationText(
                TranscriptPresentation(options.paragraphMode, options.includeTimestamps, options.includeSpeakers),
            )
            body.lines().forEach { append(paragraph(it)) }
            append("<w:sectPr/></w:body></w:document>")
        }
        val zip = ZipOutputStream(output, StandardCharsets.UTF_8)
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(CONTENT_TYPES.trimIndent().trimStart().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(ROOT_RELATIONSHIPS.trimIndent().trimStart().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("word/document.xml"))
        zip.write(xml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    private fun paragraph(text: String): String =
        "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(text)}</w:t></w:r></w:p>"

    private fun escapeXml(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when (codePoint) {
                '&'.code -> append("&amp;")
                '<'.code -> append("&lt;")
                '>'.code -> append("&gt;")
                '"'.code -> append("&quot;")
                '\''.code -> append("&apos;")
                else -> if (isXmlCodePoint(codePoint)) appendCodePoint(codePoint) else append('\uFFFD')
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isXmlCodePoint(codePoint: Int): Boolean =
        codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
            codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF

    private const val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
    """
    private const val ROOT_RELATIONSHIPS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """
}
