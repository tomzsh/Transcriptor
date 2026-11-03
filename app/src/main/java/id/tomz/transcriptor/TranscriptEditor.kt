package id.tomz.transcriptor

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.tomz.transcriptor.domain.SegmentEditError
import id.tomz.transcriptor.domain.TranscriptDocument
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranscriptSpeaker
import id.tomz.transcriptor.domain.formatTimestamp
import id.tomz.transcriptor.domain.parseTimestamp
import id.tomz.transcriptor.export.ExportFormat
import id.tomz.transcriptor.export.ExportOptions
import id.tomz.transcriptor.export.TranscriptExporter
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

internal data class TranscriptExportState(
    val busy: Boolean,
    val feedback: String?,
    val failed: Boolean,
    val export: (TranscriptDocument, ExportFormat) -> Unit,
    val exportWithOptions: (TranscriptDocument, ExportFormat, ExportOptions) -> Unit = { document, format, _ -> export(document, format) },
)

/** Own this above lazy content and navigation branches so picker results always have a receiver. */
@Composable
internal fun rememberTranscriptExportState(options: ExportOptions = ExportOptions(true, true)): TranscriptExportState {
    val currentOptions by androidx.compose.runtime.rememberUpdatedState(options)
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val exportContract = remember { ActivityResultContracts.StartActivityForResult() }
    val exportLauncher = rememberLauncherForActivityResult(exportContract) { result ->
        val path = pendingPath
        val uri = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
        pendingPath = null
        if (path != null) {
            exporting = true
            scope.launch {
                try {
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException(resources.getString(R.string.transcript_export_open_failed))
                            output.use { destination -> java.io.File(path).inputStream().use { it.copyTo(destination) } }
                        }
                        feedback = resources.getString(R.string.transcript_export_saved)
                        failed = false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val details = error.message ?: resources.getString(R.string.transcript_export_open_failed)
                    val failure = resources.getString(R.string.transcript_export_failed, details)
                    val removed = withContext(Dispatchers.IO) {
                        uri != null && runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    }
                    feedback = if (removed) failure else "$failure ${resources.getString(R.string.transcript_export_partial)}"
                    failed = true
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { java.io.File(path).delete() }
                    exporting = false
                }
            }
        }
    }

    fun createDocument(document: TranscriptDocument, format: ExportFormat, requestedOptions: ExportOptions = currentOptions) {
        // Freeze the selected original/translated output before opening an external picker.
        if (exporting || pendingPath != null) return
        val snapshot = document
        val snapshotOptions = requestedOptions
        exporting = true
        scope.launch {
            var temporary: java.io.File? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val file = java.io.File.createTempFile("transcriptor-export-", ".${format.extension}", context.cacheDir)
                    temporary = file
                    file.outputStream().use { TranscriptExporter.write(snapshot, format, snapshotOptions, it) }
                    file
                }
                val title = snapshot.project.title
                    .map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().ifBlank { "transcript" } + ".${format.extension}"
                pendingPath = prepared.absolutePath
                exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(format.mimeType).putExtra(Intent.EXTRA_TITLE, title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                temporary = null // The picker callback owns the snapshot, including after rotation.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pendingPath = null
                feedback = resources.getString(R.string.transcript_export_failed,
                    error.message ?: resources.getString(R.string.transcript_export_open_failed))
                failed = true
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { temporary?.delete() }
                exporting = false
            }
        }
    }

    return TranscriptExportState(exporting || pendingPath != null, feedback, failed,
        export = { document, format -> createDocument(document, format) },
        exportWithOptions = { document, format, selection -> createDocument(document, format, selection) })
}

@Composable
internal fun TranscriptExportControl(
    document: TranscriptDocument,
    state: TranscriptExportState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    options: ExportOptions = ExportOptions(true, true),
) {
    var menuExpanded by rememberSaveable(document.project.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled && !state.busy) {
            AppIcon(R.drawable.ic_download)
            Text(stringResource(if (state.busy) R.string.transcript_exporting else R.string.transcript_export), Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            ExportFormat.entries.forEach { format ->
                val captions = format == ExportFormat.SRT || format == ExportFormat.VTT
                DropdownMenuItem(text = { Text(if (captions) stringResource(R.string.export_subtitle_format, format.name) else format.name) },
                    enabled = enabled && !state.busy && (!captions || (options.includeTimestamps && !options.paragraphMode)),
                    onClick = { menuExpanded = false; state.exportWithOptions(document, format, options) })
            }
        }
        if (!options.includeTimestamps || options.paragraphMode) Text(stringResource(R.string.export_display_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.feedback?.let { Text(it, color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun TranscriptSpeakerEditor(
    speaker: TranscriptSpeaker,
    onRename: suspend (String, String) -> Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var name by rememberSaveable(speaker.id, speaker.name) { mutableStateOf(speaker.name) }
    var feedback by rememberSaveable(speaker.id, speaker.name) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(speaker.id, speaker.name) { mutableStateOf(false) }
    var saving by remember(speaker.id) { mutableStateOf(false) }

    Card(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                label = { Text(stringResource(R.string.transcript_speaker_name)) },
                singleLine = true,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = save@{
                    if (saving) return@save
                    val submittedName = name
                    saving = true
                    scope.launch {
                        try {
                            val saved = onRename(speaker.id, submittedName)
                            failed = !saved
                            feedback = if (saved) {
                                resources.getString(R.string.transcript_speaker_saved)
                            } else {
                                resources.getString(R.string.transcript_speaker_name_invalid)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failed = true
                            feedback = editFailureMessage(error, resources.getString(R.string.transcript_change_failed))
                        } finally { saving = false }
                    }
                },
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (saving) R.string.transcript_saving else R.string.transcript_speaker_save))
            }
            feedback?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun TranscriptSpeakerCreator(
    projectId: String,
    onCreate: suspend (String, String) -> TranscriptSpeaker?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var name by rememberSaveable(projectId) { mutableStateOf("") }
    var feedback by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(projectId) { mutableStateOf(false) }
    var saving by remember(projectId) { mutableStateOf(false) }

    Card(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                label = { Text(stringResource(R.string.transcript_speaker_new_name)) },
                singleLine = true,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = enabled && !saving,
                onClick = save@{
                    if (saving) return@save
                    val submittedName = name
                    saving = true
                    scope.launch {
                        try {
                            val speaker = onCreate(projectId, submittedName)
                            failed = speaker == null
                            feedback = resources.getString(
                                if (speaker == null) R.string.transcript_speaker_name_invalid
                                else R.string.transcript_speaker_added,
                            )
                            if (speaker != null) name = ""
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            failed = true
                            feedback = editFailureMessage(error, resources.getString(R.string.transcript_change_failed))
                        } finally { saving = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (saving) R.string.transcript_saving else R.string.transcript_speaker_add))
            }
            feedback?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun TranscriptCueEditor(
    segment: TranscriptSegment,
    speakers: List<TranscriptSpeaker>,
    onSave: suspend (TranscriptSegment) -> SegmentEditError?,
    onSeek: (Long) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    showTimestamps: Boolean = true,
    showSpeakers: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(segment.id, segment.text) { mutableStateOf(segment.text) }
    var startMs by rememberSaveable(segment.id, segment.startMs) { mutableStateOf(formatTimestamp(segment.startMs)) }
    var endMs by rememberSaveable(segment.id, segment.endMs) { mutableStateOf(formatTimestamp(segment.endMs)) }
    var speakerId by rememberSaveable(segment.id, segment.speakerId) { mutableStateOf(segment.speakerId) }
    var speakerMenuExpanded by rememberSaveable(segment.id) { mutableStateOf(false) }
    var feedback by rememberSaveable(segment.id, segment.text, segment.startMs, segment.endMs) { mutableStateOf<String?>(null) }
    var failed by rememberSaveable(segment.id, segment.text, segment.startMs, segment.endMs) { mutableStateOf(false) }
    var saving by remember(segment.id) { mutableStateOf(false) }
    val speakerName = speakers.firstOrNull { it.id == speakerId }?.name
        ?: stringResource(R.string.transcript_speaker_none)

    Card(modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.transcript_cue_number, segment.ordinal + 1),
                style = MaterialTheme.typography.titleSmall,
            )
            if (showTimestamps) TextButton(onClick = { onSeek(segment.startMs) }) {
                Text("${formatTimestamp(segment.startMs)} – ${formatTimestamp(segment.endMs)}")
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.transcript_text)) },
                minLines = 2,
                maxLines = 5,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showTimestamps) OutlinedTextField(
                value = startMs,
                onValueChange = { startMs = it },
                label = { Text(stringResource(R.string.transcript_start_time)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showTimestamps) OutlinedTextField(
                value = endMs,
                onValueChange = { endMs = it },
                label = { Text(stringResource(R.string.transcript_end_time)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showSpeakers) OutlinedButton(
                onClick = { speakerMenuExpanded = true },
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.transcript_speaker_label, speakerName))
            }
            DropdownMenu(expanded = speakerMenuExpanded && enabled && !saving, onDismissRequest = { speakerMenuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.transcript_speaker_none)) },
                    onClick = { speakerId = null; speakerMenuExpanded = false },
                )
                speakers.forEach { speaker ->
                    DropdownMenuItem(
                        text = { Text(speaker.name) },
                        onClick = { speakerId = speaker.id; speakerMenuExpanded = false },
                    )
                }
            }
            Button(
                enabled = enabled && !saving,
                onClick = save@{
                    if (saving) return@save
                    val start = parseTimestamp(startMs)
                    val end = parseTimestamp(endMs)
                    if (start == null || end == null) {
                        failed = true
                        feedback = resources.getString(R.string.transcript_edit_invalid_range)
                    } else {
                        val edited = segment.copy(startMs = start, endMs = end, text = text, speakerId = speakerId)
                        saving = true
                        scope.launch {
                            try {
                                val error = onSave(edited)
                                failed = error != null
                                feedback = resources.getString(
                                    when (error) {
                                        null -> R.string.transcript_edit_saved
                                        SegmentEditError.EMPTY_TEXT -> R.string.transcript_edit_empty_text
                                        SegmentEditError.INVALID_RANGE -> R.string.transcript_edit_invalid_range
                                        SegmentEditError.OUTSIDE_MEDIA -> R.string.transcript_edit_outside_media
                                        SegmentEditError.OVERLAPS_NEIGHBOR -> R.string.transcript_edit_overlap
                                        SegmentEditError.UNKNOWN_SPEAKER -> R.string.transcript_edit_unknown_speaker
                                        SegmentEditError.PROJECT_NOT_FOUND -> R.string.transcript_edit_project_missing
                                        SegmentEditError.SEGMENT_NOT_FOUND -> R.string.transcript_edit_segment_missing
                                        SegmentEditError.PROJECT_BUSY -> R.string.transcript_editing_locked
                                    },
                                )
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                failed = true
                                feedback = editFailureMessage(error, resources.getString(R.string.transcript_change_failed))
                            } finally { saving = false }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (saving) R.string.transcript_saving else R.string.transcript_edit_save))
            }
            feedback?.let {
                Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun editFailureMessage(error: Exception, fallback: String): String =
    if (error is IOException) error.message?.takeIf(String::isNotBlank)?.take(200) ?: fallback else fallback

private val ExportFormat.extension: String
    get() = when (this) {
        ExportFormat.TXT -> "txt"
        ExportFormat.SRT -> "srt"
        ExportFormat.VTT -> "vtt"
        ExportFormat.DOCX -> "docx"
    }

private val ExportFormat.mimeType: String
    get() = when (this) {
        ExportFormat.TXT -> "text/plain"
        ExportFormat.SRT -> "application/x-subrip"
        ExportFormat.VTT -> "text/vtt"
        ExportFormat.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
