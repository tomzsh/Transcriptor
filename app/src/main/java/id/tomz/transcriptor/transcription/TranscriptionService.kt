package id.tomz.transcriptor.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import id.tomz.transcriptor.diagnostics.AppDiagnostics
import dagger.hilt.android.AndroidEntryPoint
import id.tomz.transcriptor.R
import id.tomz.transcriptor.ProcessingClock
import id.tomz.transcriptor.data.MediaProjectRepository
import id.tomz.transcriptor.data.SegmentEntity
import id.tomz.transcriptor.data.SpeakerEntity
import id.tomz.transcriptor.data.TranscriptDao
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranslationLanguages
import id.tomz.transcriptor.translation.TextTranslator
import id.tomz.transcriptor.media.MediaAudioDecoder
import id.tomz.transcriptor.speaker.SherpaDiarizationWorker
import id.tomz.transcriptor.speaker.SpeakerTurn
import id.tomz.transcriptor.speaker.orderedSpeakerClusterIds
import id.tomz.transcriptor.inference.NativeInferenceWorker
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import id.tomz.transcriptor.model.localModelDirectory
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel

@AndroidEntryPoint
class TranscriptionService : Service() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(id.tomz.transcriptor.AppLanguageSettings.wrap(base))
    }

    @Inject
    lateinit var transcriptDao: TranscriptDao

    @Inject
    lateinit var projectRepository: MediaProjectRepository

    @Inject
    internal lateinit var audioDecoder: MediaAudioDecoder

    @Inject
    lateinit var whisperTranscriber: NativeInferenceWorker
    @Inject
    lateinit var speakerDiarizer: SherpaDiarizationWorker
    @Inject lateinit var textTranslator: TextTranslator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeProjectId: String? = null

    @Volatile
    private var latestStartId = 0
    private var startedAtMillis = 0L

    @Volatile
    private var lastNotificationProgress = -1
    @Volatile
    private var lastNotificationStage: String? = null
    private val wakeLockMonitor = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                speakerDiarizer.cancel()
                whisperTranscriber.cancel()
                textTranslator.cancel()
                activeJob?.cancel()
                if (activeJob == null) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_TRANSLATE -> Unit
            else -> {
                if (activeJob == null) stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)?.takeIf(String::isNotBlank)
            ?: run {
                if (activeJob == null) stopSelfResult(startId)
                return START_NOT_STICKY
            }
        if (activeJob?.isCompleted == false) {
            if (activeProjectId != projectId) {
                serviceScope.launch {
                    transcriptDao.project(projectId)?.let { rejected ->
                        transcriptDao.updateProject(
                            rejected.copy(error = getString(R.string.transcription_another_project_active)),
                        )
                    }
                }
            }
            return START_NOT_STICKY
        }

        startedAtMillis = System.currentTimeMillis()
        activeProjectId = projectId
        lastNotificationProgress = -1
        lastNotificationStage = null
        try {
            startAsForeground(notification(getString(R.string.transcription_starting), null), intent.action == ACTION_TRANSLATE)
            acquireWakeLock()
        } catch (error: Exception) {
            activeProjectId = null
            releaseWakeLock()
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) {
                runCatching { transcriptDao.project(projectId)?.let {
                    transcriptDao.updateProject(it.copy(error = error.message?.take(MAX_ERROR_LENGTH)
                        ?: "Android tidak dapat memulai pemrosesan"))
                } }
            } }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            if (intent.action == ACTION_TRANSLATE) {
                processTranslation(
                    projectId,
                    intent.getStringExtra(EXTRA_SOURCE_LANGUAGE).orEmpty(),
                    intent.getStringExtra(EXTRA_TARGET_LANGUAGE).orEmpty(),
                    intent.getBooleanExtra(EXTRA_ALLOW_CELLULAR, false),
                )
            } else processProject(projectId)
        }
        activeJob = job
        job.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        speakerDiarizer.cancel()
        whisperTranscriber.cancel()
                textTranslator.cancel()
        activeJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int) {
        stopAfterForegroundTimeout(startId)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopAfterForegroundTimeout(startId)
    }

    private fun stopAfterForegroundTimeout(startId: Int) {
        speakerDiarizer.cancel()
        whisperTranscriber.cancel()
                textTranslator.cancel()
        activeJob?.cancel(CancellationException("Waktu maksimum transkripsi telah tercapai"))
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun acquireWakeLock() {
        synchronized(wakeLockMonitor) {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(PowerManager::class.java)
                ?: throw IllegalStateException("Power manager tidak tersedia")
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transcription").apply {
                setReferenceCounted(false)
                acquire(MAX_WAKELOCK_MILLIS)
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockMonitor) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private suspend fun processProject(projectId: String) {
        val processingJob = currentCoroutineContext()[Job]
        val audioFile = projectRepository.normalizedAudioFile(projectId)
        val diarizationResultFile = File(audioFile.parentFile, "diarization-result.json")
        try {
            val fastDecoding = getSharedPreferences("processing", MODE_PRIVATE).getBoolean("fast_decoding", false)
            val project = transcriptDao.claimProjectForProcessing(projectId) ?: return
            audioFile.delete()
            updateNotification(getString(R.string.transcription_decoding), 0)

            withStageProgress(projectId, ProjectState.DECODING, getString(R.string.transcription_decoding)) { report ->
                audioDecoder.decodeToFloat32(projectRepository.mediaUri(project.mediaPath), audioFile) { positionMs, durationMs ->
                    val denominator = durationMs.takeIf { it > 0L } ?: project.durationMs
                    val percent = if (denominator > 0L) (positionMs * 100 / denominator).toInt().coerceIn(0, 100) else 0
                    report(percent)
                }
            }

            transcriptDao.updateProject(project.copy(state = ProjectState.TRANSCRIBING, progress = 0, error = null))
            updateNotification(getString(R.string.transcription_running), 0)
            val nativeSegments = withStageProgress(projectId, ProjectState.TRANSCRIBING, getString(R.string.transcription_running)) { report ->
                whisperTranscriber.transcribe(
                modelId = project.modelId,
                normalizedAudio = audioFile,
                language = project.language,
                fastDecoding = fastDecoding,
                    onProgress = report,
                )
            }
            val transcriptSegments = nativeSegments.mapIndexedNotNull { ordinal, native ->
                val text = native.text.trim()
                val startMs = native.startMs.coerceIn(0L, project.durationMs)
                val endMs = native.endMs.coerceIn(startMs, project.durationMs)
                if (text.isBlank() || endMs <= startMs) null else TranscriptSegment(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    ordinal = ordinal,
                    startMs = startMs,
                    endMs = endMs,
                    text = text,
                    speakerId = null,
                )
            }
            if (transcriptSegments.isEmpty()) throw IOException(getString(R.string.transcription_no_speech))
            val transcriptEntities = transcriptSegments.map { segment ->
                SegmentEntity(
                    id = segment.id,
                    projectId = segment.projectId,
                    ordinal = segment.ordinal,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                    speakerId = null,
                )
            }
            transcriptDao.replaceTranscript(projectId, emptyList(), transcriptEntities)

            var speakerWarning: String? = null
            if (project.autoSpeakers) {
                transcriptDao.updateProject(
                    project.copy(state = ProjectState.DIARIZING, progress = 0, error = null, speakerWarning = null),
                )
                updateNotification(getString(R.string.speaker_analysis), 0)
                try {
                    val segmentation = ModelCatalog.diarizationModels.first {
                        it.kind == ModelKind.DIARIZATION_SEGMENTATION
                    }
                    val embedding = ModelCatalog.diarizationModels.first {
                        it.kind == ModelKind.DIARIZATION_EMBEDDING
                    }
                    val modelDirectory = localModelDirectory(this)
                    val turns = withStageProgress(projectId, ProjectState.DIARIZING, getString(R.string.speaker_analysis)) { report ->
                        speakerDiarizer.diarize(
                        audioFile,
                        File(modelDirectory, segmentation.fileName),
                        File(modelDirectory, embedding.fileName),
                        diarizationResultFile,
                        onProgress = report)
                    }
                    val orderedClusters = orderedSpeakerClusterIds(turns)
                    val speakers = orderedClusters.mapIndexed { index, clusterId ->
                        SpeakerEntity(
                            id = "$projectId:speaker-${index + 1}",
                            projectId = projectId,
                            name = getString(R.string.speaker_name, index + 1),
                            clusterId = clusterId,
                        )
                    }
                    if (speakers.isNotEmpty()) {
                        transcriptDao.applyDiarization(projectId, speakers, turns)
                    } else {
                        speakerWarning = getString(R.string.speaker_analysis_no_speakers)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    speakerWarning = getString(R.string.speaker_analysis_failed)
                }
            }
            transcriptDao.updateProject(
                project.copy(
                    state = ProjectState.COMPLETED,
                    progress = 100,
                    error = null,
                    speakerWarning = speakerWarning,
                    translationLanguage = null,
                ),
            )
            updateNotification(getString(R.string.transcription_completed), 100)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                transcriptDao.project(projectId)?.let { current ->
                    transcriptDao.updateProject(current.copy(state = ProjectState.INTERRUPTED, error = null))
                }
            }
        } catch (error: Exception) {
            AppDiagnostics.recordFailure("processing.failed", error)
            withContext(NonCancellable + Dispatchers.IO) {
                transcriptDao.project(projectId)?.let { current ->
                    transcriptDao.updateProject(
                        current.copy(
                            state = ProjectState.FAILED,
                            error = error.message?.take(MAX_ERROR_LENGTH) ?: getString(R.string.transcription_failed),
                        ),
                    )
                }
            }
        } finally {
            diarizationResultFile.delete()
            File(diarizationResultFile.parentFile, "${diarizationResultFile.name}.part").delete()
            audioFile.delete()
            File(audioFile.parentFile, "${audioFile.name}.part").delete()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (activeJob === processingJob) {
                    ProcessingClock.clear(projectId)
                    releaseWakeLock()
                    activeJob = null
                    activeProjectId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    /** Conflation bounds callback work; draining before transitions prevents late stage updates. */
    private suspend fun <T> withStageProgress(
        projectId: String,
        state: ProjectState,
        label: String,
        block: suspend ((Int) -> Unit) -> T,
    ): T = coroutineScope {
        val stageStart = android.os.SystemClock.elapsedRealtime()
        val stage = state.name.lowercase(java.util.Locale.ROOT)
        AppDiagnostics.event("processing.stage_started", mapOf("stage" to stage))
        var success = false
        if (state != ProjectState.TRANSLATING) ProcessingClock.begin(projectId, state)
        val updates = Channel<Int>(Channel.CONFLATED)
        val writer = launch {
            var last = -1
            for (percent in updates) {
                if (percent > last) {
                    transcriptDao.updateProgress(projectId, state, percent)
                    updateNotification(label, percent)
                    last = percent
                }
            }
        }
        try {
            block { updates.trySend(it.coerceIn(0, 100)); Unit }.also { success = true }
        } finally {
            updates.close()
            withContext(NonCancellable) { writer.join() }
            AppDiagnostics.event("processing.stage_finished", mapOf("stage" to stage, "success" to success,
                "duration_ms" to android.os.SystemClock.elapsedRealtime() - stageStart))
        }
    }

    private suspend fun processTranslation(projectId: String, source: String, target: String, allowCellular: Boolean) {
        val processingJob = currentCoroutineContext()[Job]
        var claimed = false
        try {
            TranslationLanguages.validate(source, target)
            val project = transcriptDao.claimProjectForTranslation(projectId) ?: return
            claimed = true
            ProcessingClock.begin(projectId, ProjectState.DOWNLOADING_TRANSLATION)
            val originals = transcriptDao.segments(projectId)
            updateNotification(getString(R.string.translation_downloading), 0)
            withStageProgress(projectId, ProjectState.TRANSLATING, getString(R.string.translation_running)) { report ->
                val texts = textTranslator.translate(
                    originals.map { it.text }, source, target, allowCellular,
                    onModelReady = {
                        ProcessingClock.begin(projectId, ProjectState.TRANSLATING)
                        transcriptDao.updateProject(project.copy(state = ProjectState.TRANSLATING, progress = 0))
                        updateNotification(getString(R.string.translation_running), 0)
                    },
                    onProgress = report,
                )
                // Drain progress before committing the terminal state below.
                texts
            }.let { texts -> transcriptDao.saveTranslation(projectId, target, originals, texts) }
        } catch (cancelled: CancellationException) {
            if (claimed) withContext(NonCancellable) {
                transcriptDao.project(projectId)?.let {
                    transcriptDao.updateProject(it.copy(state = ProjectState.INTERRUPTED, error = null))
                }
            }
        } catch (error: Exception) {
            AppDiagnostics.recordFailure("processing.failed", error)
            withContext(NonCancellable) {
                transcriptDao.project(projectId)?.let {
                    transcriptDao.updateProject(it.copy(state = if (claimed) ProjectState.FAILED else it.state,
                        error = error.message?.take(MAX_ERROR_LENGTH)))
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (activeJob === processingJob) {
                    ProcessingClock.clear(projectId)
                    releaseWakeLock()
                    activeJob = null
                    activeProjectId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(latestStartId)
                }
            }
        }
    }

    private fun startAsForeground(notification: Notification, translating: Boolean) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                startForeground(NOTIFICATION_ID, notification, if (translating) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(stage: String, progress: Int) {
        val boundedProgress = progress.coerceIn(0, 100)
        if (boundedProgress == lastNotificationProgress && stage == lastNotificationStage) return
        lastNotificationProgress = boundedProgress
        lastNotificationStage = stage
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(getString(R.string.transcription_notification_progress, stage, boundedProgress), boundedProgress),
        )
    }

    private fun notification(content: String, progress: Int?): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.transcription_notification_title))
            .setContentText(content)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setWhen(startedAtMillis)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, id.tomz.transcriptor.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_transcription), cancelIntent)
        builder.setProgress(100, progress ?: 0, progress == null)
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transcription_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "offline_transcription"
        private const val NOTIFICATION_ID = 2104
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val ACTION_START = "id.tomz.transcriptor.transcription.START"
        private const val ACTION_CANCEL = "id.tomz.transcriptor.transcription.CANCEL"
        private const val ACTION_TRANSLATE = "id.tomz.transcriptor.transcription.TRANSLATE"
        private const val EXTRA_SOURCE_LANGUAGE = "source_language"
        private const val EXTRA_TARGET_LANGUAGE = "target_language"
        private const val EXTRA_ALLOW_CELLULAR = "allow_cellular"
        private const val MAX_ERROR_LENGTH = 400
        private const val MAX_WAKELOCK_MILLIS = 6L * 60 * 60 * 1_000

        fun start(context: Context, projectId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TranscriptionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PROJECT_ID, projectId),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranscriptionService::class.java).setAction(ACTION_CANCEL))
        }

        fun translate(context: Context, projectId: String, source: String, target: String, allowCellular: Boolean) {
            ContextCompat.startForegroundService(context,
                Intent(context, TranscriptionService::class.java).setAction(ACTION_TRANSLATE)
                    .putExtra(EXTRA_PROJECT_ID, projectId).putExtra(EXTRA_SOURCE_LANGUAGE, source)
                    .putExtra(EXTRA_TARGET_LANGUAGE, target).putExtra(EXTRA_ALLOW_CELLULAR, allowCellular))
        }
    }
}
