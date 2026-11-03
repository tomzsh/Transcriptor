package id.tomz.transcriptor

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tomz.transcriptor.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal object ProcessingClock {
    private val store = ProcessingClockStore(SystemClock::elapsedRealtime)
    val stages = store.stages

    fun begin(projectId: String, state: ProjectState) = store.begin(projectId, state)
    fun clear(projectId: String) = store.clear(projectId)
}

@Composable
internal fun ProjectProgress(project: TranscriptProject, compact: Boolean = false) {
    val active = project.state.isProcessing()
    val stageVisibleSince = remember(project.id, project.state) { SystemClock.elapsedRealtime() }
    val sharedStart = if (active) {
        val stages by ProcessingClock.stages.collectAsStateWithLifecycle()
        stages[project.id]?.takeIf { it.state == project.state }?.startedAtElapsedMs
    } else null
    val stageStartedAt = sharedStart ?: stageVisibleSince
    val progressChangedAt = remember(project.id, project.state, project.progress) { SystemClock.elapsedRealtime() }
    var now by remember(project.id, project.state) { mutableLongStateOf(stageVisibleSince) }
    LaunchedEffect(project.id, project.state) {
        if (active) while (isActive) {
            delay(1_000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val label = stringResource(when (project.state) {
        ProjectState.DECODING -> R.string.transcription_decoding
        ProjectState.TRANSCRIBING -> R.string.transcription_running
        ProjectState.DIARIZING -> R.string.speaker_analysis
        ProjectState.DOWNLOADING_TRANSLATION -> R.string.translation_downloading
        ProjectState.TRANSLATING -> R.string.translation_running
        ProjectState.COMPLETED -> R.string.library_project_completed
        ProjectState.IMPORTED -> R.string.library_project_imported
        ProjectState.INTERRUPTED -> R.string.library_project_interrupted
        ProjectState.FAILED -> R.string.library_project_failed
        ProjectState.DELETING -> R.string.library_project_processing
    })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            color = if (project.state == ProjectState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (active) {
            if (project.state == ProjectState.DOWNLOADING_TRANSLATION) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                LinearProgressIndicator(progress = { project.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(
                    if (project.state == ProjectState.TRANSCRIBING) R.string.progress_audio_processed else R.string.progress_stage,
                    project.progress.coerceIn(0, 100),
                ), style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                // A process restart can lack a stage start; label that fallback accurately.
                Text(stringResource(if (sharedStart != null) R.string.progress_process_time else R.string.progress_active_time,
                    formatMediaDuration((now - stageStartedAt).coerceAtLeast(0L))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!compact && project.state == ProjectState.TRANSCRIBING) {
                if (project.progress >= 100 || now - progressChangedAt >= 5_000) {
                    Text(stringResource(if (project.progress >= 100) R.string.progress_transcription_finalizing else R.string.progress_transcription_wait),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!compact) Text(stringResource(R.string.progress_background), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
