package id.tomz.transcriptor

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableIntStateOf
import id.tomz.transcriptor.domain.isProcessing
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import id.tomz.transcriptor.data.MediaProjectRepository
import id.tomz.transcriptor.data.ProjectDeletionResult
import id.tomz.transcriptor.model.ModelLibraryRepository
import id.tomz.transcriptor.inference.NativeInferenceWorker
import id.tomz.transcriptor.transcription.TranscriptionService
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var modelLibraryRepository: ModelLibraryRepository

    @Inject
    lateinit var mediaProjectRepository: MediaProjectRepository

    @Inject lateinit var nativeInferenceWorker: NativeInferenceWorker

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageSettings.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguageSettings.migrateToFramework(this)
        enableEdgeToEdge()
        setContent { TranscriptorApp(modelLibraryRepository, mediaProjectRepository, nativeInferenceWorker) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptorApp(
    modelRepository: ModelLibraryRepository,
    projectRepository: MediaProjectRepository,
    nativeInferenceWorker: NativeInferenceWorker,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appearance = remember { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var themeMode by rememberSaveable { mutableStateOf(runCatching { ThemeMode.valueOf(appearance.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM)) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportState = rememberTranscriptExportState()
    val previewStateHolder = rememberSaveableStateHolder()
    var modelTab by rememberSaveable { mutableIntStateOf(0) }
    val projects by projectRepository.observeProjects().collectAsStateWithLifecycle(emptyList())
    var showingModels by rememberSaveable { mutableStateOf(false) }
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(selectedProjectId) {
        val projectId = selectedProjectId
        onDispose {
            // Keep the current project through Model navigation, but never retain
            // cue editor drafts for every previously visited project in the Bundle.
            projectId?.let(previewStateHolder::removeState)
        }
    }
    var selectedModelId by rememberSaveable { mutableStateOf("whisper-tiny") }
    var selectedLanguage by rememberSaveable { mutableStateOf("auto") }
    var autoSpeakers by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingModelId by remember { mutableStateOf(selectedModelId) }
    var pendingLanguage by remember { mutableStateOf(selectedLanguage) }
    var pendingAutoSpeakers by remember { mutableStateOf(autoSpeakers) }
    var startupReady by remember { mutableStateOf(false) }
    var startupError by remember { mutableStateOf<String?>(null) }
    var startupAttempt by remember { mutableStateOf(0) }
    val selectedProject = projects.firstOrNull { it.id == selectedProjectId }
    var pendingNotificationStartProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    fun launchTranscription(projectId: String) {
        try { TranscriptionService.start(context, projectId) }
        catch (error: Exception) {
            scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.transcription_start_failed)) }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val projectId = pendingNotificationStartProjectId
        pendingNotificationStartProjectId = null
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.notification_permission_denied))
            }
        }
        projectId?.let(::launchTranscription)
    }
    fun startTranscription(projectId: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationStartProjectId = projectId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchTranscription(projectId)
        }
    }

    LaunchedEffect(projectRepository, startupAttempt) {
        startupError = null
        try {
            withContext(Dispatchers.IO) { projectRepository.prepareForStartup() }
            projectRepository.migrateLegacyMediaSources()
            startupReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (startupReady) {
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.project_migration_failed),
                )
            } else {
                startupError = error.message ?: resources.getString(R.string.project_recovery_error)
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importError = null
                try {
                    val project = projectRepository.import(
                        uri,
                        pendingModelId,
                        pendingLanguage,
                        pendingAutoSpeakers,
                    )
                    selectedProjectId = project.id
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    importError = error.message ?: resources.getString(R.string.media_open_error)
                } finally {
                    importing = false
                }
            }
        }
    }

    fun deleteProject(projectId: String) {
        scope.launch {
            try {
            when (projectRepository.deleteProject(projectId)) {
                ProjectDeletionResult.DELETED,
                ProjectDeletionResult.NOT_FOUND,
                -> if (selectedProjectId == projectId) selectedProjectId = null
                ProjectDeletionResult.ACTIVE,
                ProjectDeletionResult.STORAGE_FAILURE,
                -> snackbarHostState.showSnackbar(resources.getString(R.string.project_delete_failed))
                ProjectDeletionResult.CLEANUP_PENDING -> {
                    if (selectedProjectId == projectId) selectedProjectId = null
                    snackbarHostState.showSnackbar(resources.getString(R.string.project_delete_cleanup_pending))
                }
            }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbarHostState.showSnackbar(resources.getString(R.string.project_delete_failed)) }
        }
    }

    BackHandler(enabled = showingSettings || showingModels || selectedProjectId != null) {
        when {
            showingSettings -> showingSettings = false
            showingModels -> showingModels = false
            else -> selectedProjectId = null
        }
    }

    TranscriptorTheme(themeMode) {
        if (!startupReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (startupError == null) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.project_startup_failed, startupError.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { startupAttempt++ }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val expandedNavigation = maxWidth >= 840.dp
            val showMainNavigation = showingSettings || showingModels || selectedProject == null
            val destination = when { showingSettings -> 2; showingModels -> 1; else -> 0 }
            val selectDestination: (Int) -> Unit = { target ->
                showingSettings = target == 2
                showingModels = target == 1
                if (target == 0) selectedProjectId = null
                if (target == 1) modelTab = 0
            }
            Scaffold(
                bottomBar = {
                    if (showMainNavigation && !expandedNavigation) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            MainBarDestinations(destination, selectDestination)
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when {
                                    showingSettings -> stringResource(R.string.settings_title)
                                    showingModels -> stringResource(R.string.models_title)
                                    selectedProject != null -> selectedProject.title
                                    else -> stringResource(R.string.app_name)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (selectedProject != null) {
                                IconButton(
                                    onClick = {
                                        when { showingSettings -> showingSettings = false; showingModels -> showingModels = false; else -> selectedProjectId = null }
                                    },
                                ) {
                                    AppIcon(R.drawable.ic_arrow_left, stringResource(R.string.navigate_back))
                                }
                            }
                        },
                        actions = {},
                    )
                },
            ) { contentPadding ->
                Row(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
                    if (expandedNavigation) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, windowInsets = WindowInsets(0.dp)) {
                            MainRailDestinations(destination, selectDestination)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                if (showingSettings) {
                    AppSettingsScreen(themeMode = themeMode, onThemeChange = { mode ->
                        themeMode = mode
                        appearance.edit().putString("theme", mode.name).apply()
                    })
                } else if (showingModels) {
                    ModelLibraryScreen(modelRepository, PaddingValues(0.dp), nativeInferenceWorker, initialTab = modelTab, processing = projects.any { it.state.isProcessing() })
                } else {
                    Box(Modifier.fillMaxSize()) {
                        if (expandedNavigation) {
                            Row(Modifier.fillMaxSize()) {
                                ProjectLibraryScreen(
                                    projects = projects,
                                    modelRepository = modelRepository,
                                    onManageModels = { modelTab = 0; showingModels = true },
                                    contentPadding = PaddingValues(0.dp),
                                    selectedModelId = selectedModelId,
                                    onSelectedModelChange = { selectedModelId = it },
                                    selectedLanguage = selectedLanguage,
                                    onSelectedLanguageChange = { selectedLanguage = it },
                                    autoSpeakers = autoSpeakers,
                                    onAutoSpeakersChange = { autoSpeakers = it },
                                    importing = importing,
                                    importError = importError,
                                    onImport = { modelId, language, speakers ->
                                        pendingModelId = modelId
                                        pendingLanguage = language
                                        pendingAutoSpeakers = speakers
                                        mediaPicker.launch(arrayOf("audio/*", "video/*"))
                                    },
                                    onOpenProject = { selectedProjectId = it },
                                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                                )
                                VerticalDivider(Modifier.fillMaxHeight())
                                if (selectedProject == null) {
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(stringResource(R.string.project_selected_empty))
                                    }
                                } else {
                                    previewStateHolder.SaveableStateProvider(selectedProject.id) {
                                        MediaProjectPreview(
                                            project = selectedProject,
                                            modelRepository = modelRepository,
                                            onManageAiModels = { modelTab = 1; showingModels = true },
                                            exportState = exportState,
                                            projectRepository = projectRepository,
                                            contentPadding = PaddingValues(0.dp),
                                            onStartTranscription = { startTranscription(selectedProject.id) },
                                            onCancelTranscription = { TranscriptionService.cancel(context) },
                                            onDeleteProject = ::deleteProject,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                        )
                                    }
                                }
                            }
                        } else if (selectedProject != null) {
                            previewStateHolder.SaveableStateProvider(selectedProject.id) {
                                MediaProjectPreview(
                                    project = selectedProject,
                                    modelRepository = modelRepository,
                                    onManageAiModels = { modelTab = 1; showingModels = true },
                                    exportState = exportState,
                                    projectRepository = projectRepository,
                                    contentPadding = PaddingValues(0.dp),
                                    onStartTranscription = { startTranscription(selectedProject.id) },
                                    onCancelTranscription = { TranscriptionService.cancel(context) },
                                    onDeleteProject = ::deleteProject,
                                )
                            }
                        } else {
                            ProjectLibraryScreen(
                                projects = projects,
                                modelRepository = modelRepository,
                                onManageModels = { modelTab = 0; showingModels = true },
                                contentPadding = PaddingValues(0.dp),
                                selectedModelId = selectedModelId,
                                onSelectedModelChange = { selectedModelId = it },
                                selectedLanguage = selectedLanguage,
                                onSelectedLanguageChange = { selectedLanguage = it },
                                autoSpeakers = autoSpeakers,
                                onAutoSpeakersChange = { autoSpeakers = it },
                                importing = importing,
                                importError = importError,
                                onImport = { modelId, language, speakers ->
                                    pendingModelId = modelId
                                    pendingLanguage = language
                                    pendingAutoSpeakers = speakers
                                    mediaPicker.launch(arrayOf("audio/*", "video/*"))
                                },
                                onOpenProject = { selectedProjectId = it },
                            )
                        }
                    }
                }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MainBarDestinations(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.nav_recordings, R.string.models_title, R.string.settings_title)
    val icons = listOf(R.drawable.ic_file_audio, R.drawable.ic_cpu, R.drawable.ic_settings)
    labels.forEachIndexed { index, label ->
        NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
            icon = { AppIcon(icons[index]) }, label = { Text(stringResource(label)) })
    }
}

@Composable
private fun MainRailDestinations(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.nav_recordings, R.string.models_title, R.string.settings_title)
    val icons = listOf(R.drawable.ic_file_audio, R.drawable.ic_cpu, R.drawable.ic_settings)
    labels.forEachIndexed { index, label ->
        NavigationRailItem(selected = selected == index, onClick = { onSelect(index) },
            icon = { AppIcon(icons[index]) }, label = { Text(stringResource(label)) },
            modifier = Modifier.padding(vertical = 4.dp))
    }
}
