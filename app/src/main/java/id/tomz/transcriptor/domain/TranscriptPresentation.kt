package id.tomz.transcriptor.domain

/** Presentation only: source cue boundaries and timing remain intact for editing/playback. */
data class TranscriptPresentation(
    val paragraphMode: Boolean = true,
    val showTimestamps: Boolean = false,
    val showSpeakers: Boolean = false,
)

fun TranscriptDocument.presentationText(options: TranscriptPresentation): String {
    val names = speakers.associate { it.id to it.name }
    val ordered = segments.sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs }.thenBy { it.ordinal })
    return buildString {
        var previousSpeaker: String? = null
        ordered.forEachIndexed { index, cue ->
            val speaker = cue.speakerId?.let(names::get)?.takeIf { it.isNotBlank() }
            val speakerChanged = options.showSpeakers && cue.speakerId != previousSpeaker
            if (index > 0) append(when {
                !options.paragraphMode -> "\n"
                speakerChanged -> "\n\n"
                else -> " "
            })
            if (options.showTimestamps) append("${formatTimestamp(cue.startMs)} – ${formatTimestamp(cue.endMs)} ")
            if (options.showSpeakers && (!options.paragraphMode || index == 0 || speakerChanged) && speaker != null) {
                append(speaker).append(": ")
            }
            append(cue.text.trim().replace(Regex("\\s+"), " "))
            previousSpeaker = cue.speakerId
        }
    }
}
