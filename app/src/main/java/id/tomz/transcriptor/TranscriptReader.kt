package id.tomz.transcriptor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import id.tomz.transcriptor.domain.*

internal class PresentationState(val options: TranscriptPresentation, val update: (TranscriptPresentation) -> Unit)

@Composable
internal fun rememberPresentation(projectId: String): PresentationState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("transcript_presentation", Context.MODE_PRIVATE) }
    var paragraphs by rememberSaveable(projectId) { mutableStateOf(prefs.getBoolean("$projectId.paragraphs", true)) }
    var times by rememberSaveable(projectId) { mutableStateOf(prefs.getBoolean("$projectId.times", false)) }
    var speakers by rememberSaveable(projectId) { mutableStateOf(prefs.getBoolean("$projectId.speakers", false)) }
    return PresentationState(TranscriptPresentation(paragraphs, times, speakers)) { next ->
        paragraphs = next.paragraphMode; times = next.showTimestamps; speakers = next.showSpeakers
        prefs.edit().putBoolean("$projectId.paragraphs", paragraphs).putBoolean("$projectId.times", times)
            .putBoolean("$projectId.speakers", speakers).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptAppearance(state: PresentationState) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        AppIcon(R.drawable.ic_settings)
        Text(stringResource(R.string.reader_display), Modifier.padding(start = 8.dp))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reader_appearance_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.reader_appearance_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppearanceSwitch(stringResource(R.string.reader_paragraphs), stringResource(R.string.reader_paragraphs_help), state.options.paragraphMode) {
                state.update(state.options.copy(paragraphMode = it))
            }
            AppearanceSwitch(stringResource(R.string.reader_timestamps), stringResource(R.string.reader_timestamps_help), state.options.showTimestamps) {
                state.update(state.options.copy(showTimestamps = it))
            }
            AppearanceSwitch(stringResource(R.string.reader_speakers), stringResource(R.string.reader_speakers_help), state.options.showSpeakers) {
                state.update(state.options.copy(showSpeakers = it))
            }
            Text(stringResource(R.string.reader_preservation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { open = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
internal fun AppearanceSwitch(title: String, description: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .minimumInteractiveComponentSize().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, null, enabled = enabled)
    }
}

@Composable
internal fun TranscriptParagraph(document: TranscriptDocument, options: TranscriptPresentation, query: String) {
    val text = remember(document.segments, document.speakers, options) { document.presentationText(options) }
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    val annotated = remember(text, query, highlight, ink) {
        buildAnnotatedString {
            append(text)
            if (query.isNotBlank()) {
                var index = text.indexOf(query.trim(), ignoreCase = true)
                while (index >= 0) {
                    addStyle(SpanStyle(background = highlight, color = ink), index, index + query.trim().length)
                    index = text.indexOf(query.trim(), index + query.trim().length, ignoreCase = true)
                }
            }
        }
    }
    SelectionContainer {
        Text(annotated, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun CopyTranscript(document: TranscriptDocument, options: TranscriptPresentation) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(document.project.title, document.presentationText(options)))
        copied = true
    }) { Text(if (copied) stringResource(R.string.text_copied) else stringResource(R.string.copy_text)) }
}
