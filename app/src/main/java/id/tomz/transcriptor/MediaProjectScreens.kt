package id.tomz.transcriptor

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import id.tomz.transcriptor.data.MediaProjectRepository
import id.tomz.transcriptor.model.ModelLibraryRepository
import id.tomz.transcriptor.domain.*
import id.tomz.transcriptor.export.ExportOptions
import id.tomz.transcriptor.transcription.TranscriptionService
import java.util.Locale

@Composable
internal fun MediaProjectPreview(
    project: TranscriptProject,
    projectRepository: MediaProjectRepository,
    contentPadding: PaddingValues,
    onStartTranscription: () -> Unit,
    onCancelTranscription: () -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    exportState: TranscriptExportState = rememberTranscriptExportState(),
    onManageAiModels: () -> Unit = {},
    modelRepository: ModelLibraryRepository? = null,
) {
    key(project.id) {
        MediaProjectPreviewContent(project, projectRepository, contentPadding, onStartTranscription,
            onCancelTranscription, onDeleteProject, exportState, modifier, onManageAiModels, modelRepository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun MediaProjectPreviewContent(
    initialProject: TranscriptProject,
    projectRepository: MediaProjectRepository,
    contentPadding: PaddingValues,
    onStartTranscription: () -> Unit,
    onCancelTranscription: () -> Unit,
    onDeleteProject: (String) -> Unit,
    exportState: TranscriptExportState,
    modifier: Modifier,
    onManageAiModels: () -> Unit,
    modelRepository: ModelLibraryRepository?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val document by remember(initialProject.id) { projectRepository.observeTranscript(initialProject.id) }.collectAsStateWithLifecycle(initialValue = null)
    val project = document?.project ?: initialProject
    val presentation = rememberPresentation(project.id)
    val options = presentation.options
    var tab by rememberSaveable(project.id) { mutableIntStateOf(0) }
    // Each tab owns its drafts and scroll state when another tab leaves it uncomposed.
    // Only rememberSaveable state participates: API keys and reveal state stay ephemeral.
    val tabStateHolder = rememberSaveableStateHolder()
    var showTranslation by rememberSaveable(project.id) { mutableStateOf(false) }
    var editing by rememberSaveable(project.id) { mutableStateOf(false) }
    var query by rememberSaveable(project.id) { mutableStateOf("") }
    var searchOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    var expandedPlayback by rememberSaveable(project.id) { mutableStateOf(false) }
    var translationStartError by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var confirmReplace by rememberSaveable(project.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(project.id) { mutableStateOf(false) }
    val translated = document?.translatedDocument()
    val shown = if (showTranslation && translated != null) translated else document
    val processing = project.state.isProcessing() || project.state == ProjectState.DELETING
    val hasTranscript = document?.segments?.isNotEmpty() == true
    val player = remember(project.mediaPath) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(projectRepository.mediaUri(project.mediaPath))); prepare()
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    fun start() { if (hasTranscript) confirmReplace = true else onStartTranscription() }

    Column(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
        if (processing) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    ProjectProgress(project)
                    TextButton(onClick = onCancelTranscription) { Text(stringResource(R.string.cancel_process)) }
                }
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf(stringResource(R.string.transcript_title), stringResource(R.string.translation_result), stringResource(R.string.transcript_details)).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        // The lazy DSL runs later. Freeze its route so the outgoing list cannot
        // read the new tab and remove its draft items before their registry is saved.
        val renderedTab = tab
        key(renderedTab) {
        tabStateHolder.SaveableStateProvider(renderedTab) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (renderedTab == 0) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (showTranslation && translated != null) stringResource(R.string.translation_result) else stringResource(R.string.transcript_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        }
                        Text("${languageLabel(shown?.project?.language ?: project.language)} · ${formatMediaDuration(project.durationMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        TextButton(onClick = { expandedPlayback = !expandedPlayback }, contentPadding = PaddingValues(0.dp)) {
                            AppIcon(if (project.mediaMimeType.startsWith("video/")) R.drawable.ic_file_video else R.drawable.ic_file_audio)
                            Text(if (expandedPlayback) stringResource(R.string.playback_close) else stringResource(R.string.playback_open), Modifier.padding(start = 8.dp))
                        }
                    }
                    if (expandedPlayback) item {
                        AndroidView(factory = { viewContext -> PlayerView(viewContext).apply {
                            this.player = player; useController = true
                            if (!project.mediaMimeType.startsWith("video/")) { controllerShowTimeoutMs = 0; controllerHideOnTouch = false; showController() }
                        } }, update = { if (it.player !== player) it.player = player },
                            modifier = Modifier.fillMaxWidth().height(if (project.mediaMimeType.startsWith("video/")) 220.dp else 120.dp),
                            onRelease = { it.player = null })
                    }
                    if (!hasTranscript) item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (processing) stringResource(R.string.transcript_wait) else stringResource(R.string.transcript_invitation),
                                style = MaterialTheme.typography.bodyLarge)
                            if (!processing) Button(onClick = ::start, enabled = document != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_transcription)) }
                        }
                    }
                    if (translated != null) item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!showTranslation, { showTranslation = false; editing = false }, label = { Text(stringResource(R.string.translation_original)) })
                            FilterChip(showTranslation, { showTranslation = true; editing = false }, label = { Text(stringResource(R.string.translation_language_label, languageLabel(translated.project.language))) })
                        }
                    }
                    if (project.translationLanguage != null && translated == null) item {
                        Text(stringResource(R.string.translation_stale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    shown?.takeIf { it.segments.isNotEmpty() }?.let { transcript ->
                        item {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CopyTranscript(transcript, options)
                                TranscriptAppearance(presentation)
                                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                                    AppIcon(if (searchOpen) R.drawable.ic_x else R.drawable.ic_search,
                                        stringResource(if (searchOpen) R.string.close_search else R.string.transcript_search))
                                }
                                if (!showTranslation) TextButton(onClick = { editing = !editing }, enabled = !processing) {
                                    Text(if (editing) stringResource(R.string.reader_return) else stringResource(R.string.edit_segments))
                                }
                            }
                            if (searchOpen) OutlinedTextField(query, { query = it }, singleLine = true,
                                label = { Text(stringResource(R.string.transcript_search)) },
                                leadingIcon = { AppIcon(R.drawable.ic_search) }, modifier = Modifier.fillMaxWidth())
                        }
                        if (query.isNotBlank() && transcript.segments.none { it.text.contains(query.trim(), true) }) item { Text(stringResource(R.string.transcript_text_no_results)) }
                        if (editing && !showTranslation) {
                            items(transcript.segments.filter { it.text.contains(query.trim(), true) }, key = { it.id }) { segment ->
                                TranscriptCueEditor(segment, transcript.speakers, projectRepository::updateSegment,
                                    { player.seekTo(it); expandedPlayback = true }, enabled = !processing,
                                    showTimestamps = options.showTimestamps, showSpeakers = options.showSpeakers)
                            }
                        } else item { TranscriptParagraph(transcript, options, query) }
                        item { HorizontalDivider() }
                        item { TranscriptExportControl(transcript, exportState, enabled = !processing,
                            options = ExportOptions(options.showTimestamps, options.showSpeakers, options.paragraphMode)) }
                    }
                } else if (renderedTab == 1) {
                    item {
                        document?.let { original ->
                            TranslationControls(original, enabled = !processing, onManageModels = onManageAiModels, modelRepository = modelRepository, onTranslate = { source, target, cellular ->
                                try {
                                    translationStartError = null
                                    TranscriptionService.translate(context, project.id, source, target, cellular)
                                } catch (error: Exception) { translationStartError = resources.getString(R.string.translation_launch_failed) }
                            })
                        }
                    }
                    translationStartError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (translated != null) item {
                        Button(onClick = { showTranslation = true; editing = false; tab = 0 }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_translation)) }
                    }
                } else {
                    item {
                        Text(stringResource(R.string.recording_settings), style = MaterialTheme.typography.headlineSmall)
                        Text("${project.title}\n${formatMediaDuration(project.durationMs)} · ${project.modelId}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { PerformanceOptions() }
                    item { if (!processing) OutlinedButton(onClick = ::start, enabled = document != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (hasTranscript) R.string.transcription_rerun else R.string.start_transcription)) } }
                    document?.let { transcript ->
                        item {
                            Text(stringResource(R.string.speakers_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.speakers_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item { TranscriptSpeakerCreator(project.id, projectRepository::createSpeaker, !processing) }
                        items(transcript.speakers, key = { it.id }) { TranscriptSpeakerEditor(it, projectRepository::renameSpeaker, !processing) }
                    }
                    item { HorizontalDivider() }
                    item { TextButton(onClick = { confirmDelete = true }, enabled = !processing) { AppIcon(R.drawable.ic_trash_2); Text(stringResource(R.string.project_delete), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.error) } }
                }
                project.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                project.speakerWarning?.let { item { Text(it, color = MaterialTheme.colorScheme.tertiary) } }
            }
            }
        }
        }
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.transcription_confirm_replace_title)) },
        text = { Text(stringResource(R.string.transcription_confirm_replace_message)) },
        confirmButton = { TextButton(onClick = { confirmReplace = false; onStartTranscription() }) { Text(stringResource(R.string.transcription_confirm_replace)) } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.transcription_keep_existing)) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.project_delete_confirm_title)) }, text = { Text(stringResource(R.string.project_delete_confirm_message)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteProject(project.id) }) { Text(stringResource(R.string.project_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.project_delete_cancel)) } })
}

internal fun formatMediaDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
