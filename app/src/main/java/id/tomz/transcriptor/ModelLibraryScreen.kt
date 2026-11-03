package id.tomz.transcriptor

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.tomz.transcriptor.model.*
import id.tomz.transcriptor.translation.*
import id.tomz.transcriptor.inference.NativeInferenceWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelLibraryScreen(
    repository: ModelLibraryRepository,
    contentPadding: PaddingValues,
    nativeInferenceWorker: NativeInferenceWorker,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    processing: Boolean = false,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember { OfflineModelStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var selected by remember { mutableStateOf(store.selectedModelId) }
    var selecting by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var removingImport by remember { mutableStateOf(false) }
    var importProgress by remember { mutableIntStateOf(0) }
    var importedName by remember { mutableStateOf(store.importedModel?.let { store.importedModelName }) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackFailed by remember { mutableStateOf(false) }
    var accelerationInfo by remember { mutableStateOf<ComputeAccelerationInfo?>(null) }
    LaunchedEffect(processing) {
        if (!processing) {
            accelerationInfo = try { nativeInferenceWorker.detect() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ComputeAccelerationInfo(emptyList(), emptyList(), false, false) }
        }
    }
    val canManage = !processing && selecting == null && !importing && !removingImport && state.busyModelId == null && !state.isRefreshing
    fun select(id: String) {
        scope.launch {
            selecting = id; feedback = null; feedbackFailed = false
            try { store.selectModel(id); selected = store.selectedModelId; feedback = resources.getString(R.string.models_active_updated) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: resources.getString(R.string.models_activation_failed); feedbackFailed = true }
            finally { selecting = null }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; importProgress = 0; feedback = null; feedbackFailed = false
            try {
                store.importModel(uri) { importProgress = it }
                selected = store.selectedModelId
                importedName = store.selectedModelName
                feedback = resources.getString(R.string.models_import_ready)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { feedback = error.message ?: resources.getString(R.string.models_import_failed); feedbackFailed = true }
            finally { importing = false }
        }
    }
    Column(modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf(stringResource(R.string.models_tab_transcription), stringResource(R.string.models_tab_ai), stringResource(R.string.speakers_title)).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label, maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Text(when (tab) { 0 -> stringResource(R.string.models_speech_title); 1 -> stringResource(R.string.models_ai_title); else -> stringResource(R.string.models_speaker_title) }, style = MaterialTheme.typography.headlineSmall)
                Text(when (tab) {
                    0 -> stringResource(R.string.models_speech_help)
                    1 -> stringResource(R.string.models_ai_help)
                    else -> stringResource(R.string.speaker_models_description)
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.models_download_help),
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.isRefreshing && state.busyModelId == null) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.models_refreshing), style = MaterialTheme.typography.bodySmall)
            }
            if (processing) item { Text(stringResource(R.string.models_processing_lock), style = MaterialTheme.typography.bodySmall) }
            if (tab == 1) item { ComputeAccelerationCard(accelerationInfo) }
            feedback?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (feedbackFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } }
            val entries = state.entries.filter { when (tab) {
                0 -> it.spec.kind == ModelKind.WHISPER
                1 -> it.spec.kind == ModelKind.AI_TRANSLATION
                else -> it.spec.kind == ModelKind.DIARIZATION_SEGMENTATION || it.spec.kind == ModelKind.DIARIZATION_EMBEDDING
            } }
            items(entries, key = { it.spec.id }) { entry ->
                ModelCard(entry, state, repository, active = entry.installed && selected == entry.spec.id,
                    selecting = selecting == entry.spec.id, canManage = canManage,
                    onSelect = { select(entry.spec.id) }, onLicence = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { feedback = resources.getString(R.string.browser_unavailable); feedbackFailed = true }
                    })
            }
            if (tab == 1) {
                item { HorizontalDivider() }
                item {
                    Text(stringResource(R.string.models_custom_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.models_custom_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    importedName?.let {
                        Text(it, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        if (selected == IMPORTED_MODEL_ID) Text(stringResource(R.string.active), color = MaterialTheme.colorScheme.primary)
                        else OutlinedButton(onClick = { select(IMPORTED_MODEL_ID) }, enabled = canManage) { Text(stringResource(R.string.models_use_imported)) }
                        TextButton(onClick = { scope.launch {
                            removingImport = true
                            try { store.removeImportedModel(); importedName = null; selected = store.selectedModelId }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { feedback = error.message; feedbackFailed = true }
                            finally { removingImport = false }
                        } }, enabled = canManage) { Text(stringResource(R.string.models_remove_imported)) }
                    }
                    if (removingImport) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.models_removing_import)) }
                    if (importing) {
                        LinearProgressIndicator(progress = { importProgress / 100f }, Modifier.fillMaxWidth())
                        Text(stringResource(R.string.models_import_progress, importProgress), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = canManage, modifier = Modifier.fillMaxWidth()) {
                        AppIcon(R.drawable.ic_upload); Text(stringResource(R.string.models_import_file), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ComputeAccelerationCard(info: ComputeAccelerationInfo?) {
    val selected = info?.devices?.let(ComputeBackendPolicy::choose)
    val gpu = info?.devices?.firstOrNull { device ->
        val backend = device.backend.lowercase()
        backend.contains("vulkan") || backend.contains("opencl")
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                AppIcon(R.drawable.ic_cpu)
                Text(stringResource(R.string.acceleration_title), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
                AppIcon(if (expanded) R.drawable.ic_x else R.drawable.ic_chevron_down)
            }
            if (!expanded) Text(if (info == null) stringResource(R.string.acceleration_checking)
                else selected?.let { "${it.backend} · ${it.description.ifBlank { it.name }}" } ?: "CPU",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
            if (info == null) {
                Text(stringResource(R.string.acceleration_checking), style = MaterialTheme.typography.bodyMedium)
            } else {
                val translation = selected?.let { "${it.backend} · ${it.description.ifBlank { it.name }}" } ?: "CPU"
                val whisper = gpu?.let { "${it.backend} · ${it.description.ifBlank { it.name }} + CPU fallback" } ?: "CPU"
                if (!info.nativeRuntimeAvailable) {
                    Text(stringResource(R.string.acceleration_unavailable), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.acceleration_translation, translation), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.acceleration_whisper, whisper), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.acceleration_gpu_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.openClDriverDetected && info.devices.none { it.backend.contains("opencl", ignoreCase = true) }) {
                    Text(stringResource(R.string.acceleration_opencl),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (info.nnapiAccelerators.isNotEmpty()) {
                    Text(stringResource(R.string.acceleration_nnapi, info.nnapiAccelerators.joinToString()), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.acceleration_apu_unused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.acceleration_apu_absent),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            }
        }
    }
}

@Composable
private fun ModelCard(entry: ModelLibraryEntryState, state: ModelLibraryState, repository: ModelLibraryRepository,
    active: Boolean, selecting: Boolean, canManage: Boolean, onSelect: () -> Unit, onLicence: (String) -> Unit) {
    val spec = entry.spec
    val useLabel = stringResource(R.string.models_use_named, spec.label)
    val removeLabel = stringResource(R.string.models_remove_named, spec.label)
    val downloadLabel = stringResource(R.string.models_download_named, spec.label)
    val busy = state.busyModelId == spec.id
    val progress = if (state.downloadTotalBytes > 0) (state.downloadedBytes.toFloat() / state.downloadTotalBytes).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(spec.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (entry.installed) AppIcon(R.drawable.ic_check, stringResource(R.string.model_installed))
            }
            Text("${formatSize(spec.bytes)} · ${if (spec.id == "ai-translategemma-4b") stringResource(R.string.model_license_translategemma) else spec.licence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val description = modelDescription(spec)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.licenceUrl?.let { link -> TextButton(onClick = { onLicence(link) }) { Text(stringResource(R.string.models_read_license)) } }
                if (spec.id == "whisper-cahya-medium-id-q5") {
                    TextButton(onClick = { onLicence("https://huggingface.co/cahya/whisper-medium-id") }) { Text(stringResource(R.string.model_source)) }
                    TextButton(onClick = { onLicence("https://huggingface.co/rafdiraf/whisper-medium-id-ggml") }) { Text(stringResource(R.string.model_conversion_source)) }
                }
            }
            when {
                busy && state.isVerifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.bodySmall)
                }
                busy && state.isDownloading -> {
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}% · ${formatSize(state.downloadedBytes)} / ${formatSize(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
                    if (state.downloadStatus == PlatformDownloadState.PAUSED) Text(stringResource(R.string.models_waiting_wifi), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = repository::cancelDownload) { Text(stringResource(R.string.cancel_download)) }
                }
                busy || selecting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (selecting) stringResource(R.string.models_activating) else stringResource(R.string.models_preparing), style = MaterialTheme.typography.bodySmall)
                }
                entry.installed -> {
                    Text(if (active) stringResource(R.string.models_active_offline) else stringResource(R.string.model_installed), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    if (spec.kind == ModelKind.AI_TRANSLATION && !active) Button(onClick = onSelect, enabled = canManage,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = useLabel }) { Text(stringResource(R.string.models_use)) }
                    OutlinedButton(onClick = { repository.remove(spec.id) }, enabled = canManage,
                        modifier = Modifier.semantics { contentDescription = removeLabel }) { Text(stringResource(R.string.models_remove)) }
                }
                else -> Button(onClick = { repository.download(spec.id) }, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = downloadLabel }) {
                    AppIcon(R.drawable.ic_download); Text(stringResource(R.string.download_model), Modifier.padding(start = 8.dp))
                }
            }
            if (state.errorModelId == spec.id) state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun formatSize(bytes: Long): String = "${(bytes + 524_288L) / 1_048_576L} MiB"

@Composable
private fun modelDescription(spec: ModelSpec): String = when (spec.id) {
    "whisper-cahya-medium-id-q5" -> stringResource(R.string.model_description_cahya_medium_id)
    "whisper-small-q5" -> stringResource(R.string.model_description_small_q5)
    "whisper-turbo-q5" -> stringResource(R.string.model_description_turbo_q5)
    "ai-qwen35-0.8b" -> stringResource(R.string.model_description_qwen_small)
    "ai-qwen35-2b" -> stringResource(R.string.model_description_qwen_large)
    "ai-translategemma-4b" -> stringResource(R.string.model_description_translategemma)
    "ai-gemma4-e2b" -> stringResource(R.string.model_description_gemma)
    "ai-llama32-1b" -> stringResource(R.string.model_description_llama)
    else -> spec.description
}
