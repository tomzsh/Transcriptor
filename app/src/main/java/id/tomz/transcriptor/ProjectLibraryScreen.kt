package id.tomz.transcriptor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tomz.transcriptor.domain.*
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelLibraryRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectLibraryScreen(
    projects: List<TranscriptProject>,
    modelRepository: ModelLibraryRepository,
    contentPadding: PaddingValues,
    selectedModelId: String,
    onSelectedModelChange: (String) -> Unit,
    selectedLanguage: String,
    onSelectedLanguageChange: (String) -> Unit,
    autoSpeakers: Boolean,
    onAutoSpeakersChange: (Boolean) -> Unit,
    importing: Boolean,
    importError: String?,
    onImport: (modelId: String, language: String, autoSpeakers: Boolean) -> Unit,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    onManageModels: () -> Unit = {},
) {
    val modelState by modelRepository.state.collectAsStateWithLifecycle()
    val selectedModel = ModelCatalog.whisperModels.firstOrNull { it.id == selectedModelId } ?: ModelCatalog.whisperModels.first()
    val installed = modelState.entries.any { it.spec.id == selectedModel.id && it.installed }
    val speakerModelsInstalled = ModelCatalog.diarizationModels.all { model -> modelState.entries.any { it.spec.id == model.id && it.installed } }
    var modelMenu by remember { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(projects, query) { projects.filter { it.title.contains(query.trim(), ignoreCase = true) } }
    val language = TranscriptionLanguages.forModel(selectedLanguage, selectedModel.englishOnly, selectedModel.transcriptionLanguage)

    LazyColumn(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(stringResource(R.string.library_new_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.library_new_description), Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.new_recording), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.library_import_description), Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(selectedModel.label, style = MaterialTheme.typography.titleSmall)
                            Text(languageLabel(language), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { settingsExpanded = !settingsExpanded }, enabled = !importing) {
                            AppIcon(R.drawable.ic_settings)
                            Text(if (settingsExpanded) stringResource(R.string.close) else stringResource(R.string.configure), Modifier.padding(start = 8.dp))
                        }
                    }
                    if (!installed && !settingsExpanded) TextButton(onClick = onManageModels) { Text(stringResource(R.string.download_transcription_model)) }
                    if (settingsExpanded) ModalBottomSheet(onDismissRequest = { settingsExpanded = false }) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.library_import_options), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.library_model_picker), style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { modelMenu = true }, enabled = !importing,
                            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                            AppIcon(R.drawable.ic_cpu)
                            Text(selectedModel.label, Modifier.weight(1f).padding(horizontal = 12.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            AppIcon(R.drawable.ic_chevron_down)
                        }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            ModelCatalog.whisperModels.forEach { spec ->
                                val available = modelState.entries.any { it.spec.id == spec.id && it.installed }
                                DropdownMenuItem(text = { Column {
                                    Text(spec.label)
                                    Text(stringResource(if (available) R.string.model_installed else R.string.model_not_installed),
                                        style = MaterialTheme.typography.labelSmall)
                                } }, leadingIcon = { if (selectedModel.id == spec.id) AppIcon(R.drawable.ic_check) },
                                    onClick = {
                                        onSelectedLanguageChange(TranscriptionLanguages.forModel(selectedLanguage, spec.englishOnly, spec.transcriptionLanguage))
                                        onSelectedModelChange(spec.id); modelMenu = false
                                    })
                            }
                        }
                    }
                    Text(stringResource(R.string.library_model_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!installed) {
                        TextButton(onClick = { settingsExpanded = false; onManageModels() }) {
                            AppIcon(R.drawable.ic_download)
                            Text(stringResource(R.string.library_model_manage), Modifier.padding(start = 8.dp))
                        }
                    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppIcon(R.drawable.ic_check)
                        Text(stringResource(R.string.library_model_available), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LanguagePicker(stringResource(R.string.library_language), language,
                        when {
                            selectedModel.transcriptionLanguage != null -> listOf(languageOption(selectedModel.transcriptionLanguage))
                            selectedModel.englishOnly -> listOf(languageOption("en"))
                            else -> TranscriptionLanguages.all
                        },
                        onSelect = onSelectedLanguageChange, enabled = !importing,
                        allowAuto = !selectedModel.englishOnly && selectedModel.transcriptionLanguage == null)
                    if (selectedModel.transcriptionLanguage != null) Text(stringResource(R.string.library_fixed_language),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selectedModel.englishOnly) Text(stringResource(R.string.library_model_english_only),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().toggleable(value = autoSpeakers, enabled = !importing,
                        onValueChange = onAutoSpeakersChange, role = Role.Checkbox).minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(autoSpeakers, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.library_speakers), style = MaterialTheme.typography.bodyMedium)
                            if (!speakerModelsInstalled) Text(stringResource(R.string.library_speaker_help),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (autoSpeakers && !speakerModelsInstalled) TextButton(onClick = { settingsExpanded = false; onManageModels() }) {
                        Text(stringResource(R.string.models_title))
                    }
                    Button(onClick = { settingsExpanded = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.done))
                    }
                    }
                    }
                    Button(onClick = { onImport(selectedModel.id, language, autoSpeakers) }, enabled = !importing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(16.dp)) {
                        AppIcon(R.drawable.ic_upload)
                        Text(stringResource(R.string.import_media), Modifier.padding(start = 10.dp))
                    }
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.library_importing), style = MaterialTheme.typography.bodySmall)
                    }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(R.drawable.ic_shield_check)
                Text(stringResource(R.string.library_offline), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.library_projects), style = MaterialTheme.typography.titleLarge)
                if (projects.isNotEmpty()) Text(stringResource(R.string.library_saved_count, projects.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (projects.isNotEmpty()) item {
            OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.library_project_search)) }, leadingIcon = { AppIcon(R.drawable.ic_search) })
        }
        if (projects.isEmpty()) item {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(R.drawable.ic_file_text)
                Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.library_empty_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (filtered.isEmpty()) item { Text(stringResource(R.string.library_no_results)) }
        items(filtered, key = { it.id }) { project ->
            Surface(onClick = { onOpenProject(project.id) }, modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            AppIcon(if (project.mediaMimeType.startsWith("video/")) R.drawable.ic_file_video else R.drawable.ic_file_audio)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(project.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${formatMediaDuration(project.durationMs)} · ${ModelCatalog.get(project.modelId)?.label ?: project.modelId}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ProjectProgress(project, compact = true)
                    }
                    AppIcon(R.drawable.ic_chevron_right)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
