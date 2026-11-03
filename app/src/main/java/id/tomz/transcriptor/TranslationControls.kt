package id.tomz.transcriptor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tomz.transcriptor.domain.*
import id.tomz.transcriptor.model.ModelLibraryRepository
import id.tomz.transcriptor.translation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationControls(document: TranscriptDocument, enabled: Boolean,
    onTranslate: (source: String, target: String, allowCellular: Boolean) -> Unit,
    onManageModels: () -> Unit = {}, modelRepository: ModelLibraryRepository? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = remember { AiTranslationSettings(context.applicationContext) }
    val store = remember { OfflineModelStore(context.applicationContext) }
    val catalog = modelRepository?.state?.collectAsStateWithLifecycle()?.value
    val selectedId = store.selectedModelId
    val installed = if (selectedId == IMPORTED_MODEL_ID) store.importedModel != null
        else catalog?.entries?.firstOrNull { it.spec.id == selectedId }?.installed ?: (store.modelFile != null)
    val scope = rememberCoroutineScope()
    val initial = remember { runCatching { settings.read() }.getOrDefault(AiTranslationConfig()) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var guidance by rememberSaveable { mutableStateOf(initial.guidance) }
    var revealKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var source by rememberSaveable(document.project.id) { mutableStateOf(document.project.language.takeIf(TranslationLanguages::supports).orEmpty()) }
    var target by rememberSaveable(document.project.id) { mutableStateOf(document.project.translationLanguage ?: if (source == "id") "en" else "id") }
    val available = enabled && !saving
    suspend fun save(): Boolean {
        val snapshot = AiTranslationConfig(mode, endpoint.trim(), model.trim(), key.trim(), guidance.trim())
        saving = true
        return try {
            withContext(Dispatchers.IO) { settings.save(snapshot) }
            saved = true; error = null; true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = resources.getString(R.string.translation_save_failed); false }
        finally { saving = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.translation_context_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.translation_context_description),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AiTranslationMode.entries.forEachIndexed { index, item ->
                SegmentedButton(selected = mode == item, onClick = { mode = item; saved = false }, enabled = available,
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(if (item == AiTranslationMode.CLOUD) stringResource(R.string.translation_mode_cloud) else stringResource(R.string.translation_mode_offline)) }
            }
        }
        if (mode == AiTranslationMode.CLOUD) {
            Text(stringResource(R.string.translation_cloud_disclosure),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(endpoint, { endpoint = it; saved = false }, label = { Text(stringResource(R.string.translation_endpoint)) },
                supportingText = { Text(stringResource(R.string.translation_endpoint_help)) }, singleLine = true,
                enabled = available, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it; saved = false }, label = { Text(stringResource(R.string.translation_cloud_model)) }, singleLine = true,
                enabled = available, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it; saved = false }, label = { Text(stringResource(R.string.translation_api_key)) }, singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) stringResource(R.string.close) else stringResource(R.string.show)) } },
                enabled = available, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.translation_key_storage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (installed) store.selectedModelName ?: stringResource(R.string.translation_active_model) else stringResource(R.string.translation_choose_model), style = MaterialTheme.typography.titleMedium)
                    Text(if (installed) stringResource(R.string.translation_offline_ready)
                        else stringResource(R.string.translation_offline_setup),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onManageModels, enabled = available, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(R.drawable.ic_cpu); Text(stringResource(R.string.translation_manage_models), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        LanguagePicker(stringResource(R.string.translation_source), source, TranslationLanguages.all, { source = it }, enabled = available)
        LanguagePicker(stringResource(R.string.translation_target), target, TranslationLanguages.all, { target = it }, enabled = available)
        OutlinedTextField(guidance, { guidance = it; saved = false }, label = { Text(stringResource(R.string.translation_guidance)) },
            placeholder = { Text(stringResource(R.string.translation_guidance_example)) },
            minLines = 3, maxLines = 6, supportingText = { Text(if (mode == AiTranslationMode.OFFLINE) stringResource(R.string.translation_guidance_offline_limit) else stringResource(R.string.translation_guidance_cloud_limit)) },
            enabled = available, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = { scope.launch { save() } }, enabled = available, modifier = Modifier.fillMaxWidth()) { Text(if (saved) stringResource(R.string.translation_settings_saved) else stringResource(R.string.translation_save_settings)) }
        Button(onClick = { val from = source; val to = target; scope.launch { if (save()) onTranslate(from, to, false) } },
            enabled = available && document.segments.isNotEmpty() && source != target && TranslationLanguages.supports(source) && TranslationLanguages.supports(target)
                && (if (mode == AiTranslationMode.CLOUD) key.isNotBlank() && model.isNotBlank() else installed), modifier = Modifier.fillMaxWidth()) {
            AppIcon(R.drawable.ic_languages); Text(stringResource(R.string.translation_start), Modifier.padding(start = 8.dp))
        }
        if (document.segments.isEmpty()) Text(stringResource(R.string.translation_needs_transcript), style = MaterialTheme.typography.bodySmall)
    }
}
