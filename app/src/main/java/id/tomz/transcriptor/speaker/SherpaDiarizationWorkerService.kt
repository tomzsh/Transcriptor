package id.tomz.transcriptor.speaker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class SherpaDiarizationWorkerService : Service() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(id.tomz.transcriptor.AppLanguageSettings.wrap(base))
    }

    @Inject
    lateinit var diarizer: SherpaSpeakerDiarizer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var activeJob: Job? = null

    private val binder = object : IDiarizationService.Stub() {
        override fun start(
            pcmPath: String,
            segmentationPath: String,
            embeddingPath: String,
            resultPath: String,
            callback: IDiarizationCallback,
        ) {
            val rejected = synchronized(lock) {
                if (activeJob?.isActive == true) {
                    true
                } else {
                    val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                        runDiarization(pcmPath, segmentationPath, embeddingPath, resultPath, callback)
                    }
                    activeJob = job
                    job.start()
                    false
                }
            }
            if (rejected) runCatching { callback.onError("Pengelompokan pembicara lain sedang berjalan") }
        }

        override fun cancel() {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    private suspend fun runDiarization(
        pcmPath: String,
        segmentationPath: String,
        embeddingPath: String,
        resultPath: String,
        callback: IDiarizationCallback,
    ) {
        val job = currentCoroutineContext()[Job]
        try {
            val resultFile = File(resultPath)
            if (!resultFile.isAbsolute) throw IOException("Lokasi hasil diarization tidak valid")
            val turns = diarizer.diarize(
                File(pcmPath),
                File(segmentationPath),
                File(embeddingPath),
            ) { processed, total ->
                runCatching { callback.onProgress(processed, total) }
            }
            currentCoroutineContext().ensureActive()
            writeDiarizationResult(resultFile, turns)
            currentCoroutineContext().ensureActive()
            runCatching { callback.onCompleted(resultFile.absolutePath) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching {
                callback.onError(error.message?.take(MAX_ERROR_LENGTH) ?: "Pengelompokan pembicara gagal")
            }
        } finally {
            synchronized(lock) {
                if (activeJob === job) activeJob = null
            }
        }
    }

    private fun writeDiarizationResult(file: File, turns: List<SpeakerTurn>) {
        val parent = file.absoluteFile.parentFile
            ?: throw IOException("Lokasi hasil diarization tidak valid")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Tidak dapat menyiapkan berkas hasil diarization")
        val temporary = File(parent, "${file.name}.part")
        try {
            val json = JSONArray().apply {
                turns.forEach { turn ->
                    put(
                        JSONObject()
                            .put("startMs", turn.startMs)
                            .put("endMs", turn.endMs)
                            .put("clusterId", turn.clusterId),
                    )
                }
            }.toString()
            FileOutputStream(temporary, false).use { output ->
                output.write(json.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}
