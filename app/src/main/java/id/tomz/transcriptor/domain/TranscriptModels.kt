package id.tomz.transcriptor.domain

/** Durable state of a media project and its local transcription job. */
enum class ProjectState {
    IMPORTED,
    DECODING,
    TRANSCRIBING,
    DIARIZING,
    DOWNLOADING_TRANSLATION,
    TRANSLATING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    DELETING,
}

data class TranscriptProject(
    val id: String,
    val title: String,
    val mediaPath: String,
    val mediaMimeType: String,
    val durationMs: Long,
    val modelId: String,
    val language: String,
    val autoSpeakers: Boolean,
    val state: ProjectState,
    val speakerWarning: String? = null,
    val error: String? = null,
    val progress: Int = 0,
    val translationLanguage: String? = null,
)

data class TranscriptSegment(
    val id: String,
    val projectId: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String?,
    val translatedText: String? = null,
)

data class TranscriptSpeaker(
    val id: String,
    val projectId: String,
    val name: String,
    val clusterId: Int? = null,
)

data class TranscriptDocument(
    val project: TranscriptProject,
    val segments: List<TranscriptSegment>,
    val speakers: List<TranscriptSpeaker>,
)

fun ProjectState.isProcessing(): Boolean = this in setOf(
    ProjectState.DECODING, ProjectState.TRANSCRIBING, ProjectState.DIARIZING,
    ProjectState.DOWNLOADING_TRANSLATION, ProjectState.TRANSLATING,
)

/** A translation is exportable only when every cue still has a valid translated text. */
fun TranscriptDocument.translatedDocument(): TranscriptDocument? {
    val target = project.translationLanguage ?: return null
    if (segments.isEmpty() || segments.any { it.translatedText.isNullOrBlank() }) return null
    return copy(
        project = project.copy(language = target, title = "${project.title} ($target)"),
        segments = segments.map { it.copy(text = it.translatedText!!) },
    )
}

enum class SegmentEditError {
    EMPTY_TEXT,
    INVALID_RANGE,
    OUTSIDE_MEDIA,
    OVERLAPS_NEIGHBOR,
    UNKNOWN_SPEAKER,
    PROJECT_NOT_FOUND,
    SEGMENT_NOT_FOUND,
    PROJECT_BUSY,
}

/** Returns an edit error without mutating the saved segment. Adjacent cues may touch exactly. */
fun validateSegmentEdit(
    segment: TranscriptSegment,
    previous: TranscriptSegment?,
    next: TranscriptSegment?,
    durationMs: Long,
    speakerIds: Set<String>,
): SegmentEditError? {
    if (segment.text.isBlank()) return SegmentEditError.EMPTY_TEXT
    if (segment.startMs < 0 || segment.endMs <= segment.startMs) return SegmentEditError.INVALID_RANGE
    if (durationMs < 0 || segment.endMs > durationMs) return SegmentEditError.OUTSIDE_MEDIA
    if ((previous != null && previous.endMs > segment.startMs) ||
        (next != null && next.startMs < segment.endMs)
    ) return SegmentEditError.OVERLAPS_NEIGHBOR
    if (segment.speakerId != null && segment.speakerId !in speakerIds) return SegmentEditError.UNKNOWN_SPEAKER
    return null
}
