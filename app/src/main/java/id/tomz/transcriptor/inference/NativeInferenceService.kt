package id.tomz.transcriptor.inference

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import dagger.hilt.android.AndroidEntryPoint
import id.tomz.transcriptor.translation.ComputeAccelerationDetector
import id.tomz.transcriptor.translation.LocalAiEngine
import id.tomz.transcriptor.whisper.WhisperTranscriber
import id.tomz.transcriptor.diagnostics.AppDiagnostics
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class NativeInferenceService : Service() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(id.tomz.transcriptor.AppLanguageSettings.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        currentService.set(this)
    }
    @Inject lateinit var whisper: WhisperTranscriber
    @Inject lateinit var local: LocalAiEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitor = Any()
    private var job: Job? = null

    private val binder = object : IInferenceService.Stub() {
        override fun execute(requestPath: String, resultPath: String, callback: IInferenceCallback) {
            synchronized(monitor) {
                if (job != null) {
                    runCatching { callback.onError("Proses native lain masih aktif") }
                    return
                }
                job = scope.launch(start = CoroutineStart.LAZY) {
                    var failure: String? = null
                    try {
                        val requestFile = checkedFile(requestPath)
                        val resultFile = checkedFile(resultPath)
                        check(requestFile.length() <= 256 * 1024) { "Permintaan native terlalu besar" }
                        val request = JSONObject(requestFile.readText(Charsets.UTF_8))
                        val useGpu = request.optBoolean("useGpu", true)
                        val threads = request.optInt("threads", 3).coerceIn(1, 4)
                        val batchTokens = request.optInt("batchTokens", 64).coerceIn(16, 128)
                        val priority = request.optInt("priority", 5).coerceIn(0, 10)
                        // The backend registry probes Vulkan even for some CPU APIs. Set
                        // its upstream disable flag before the first JNI call in a retry process.
                        if (!useGpu) android.system.Os.setenv("GGML_DISABLE_VULKAN", "1", true)
                        fun progress(value: Int) { runCatching { callback.onProgress(value) } }
                        val result = when (request.getString("operation")) {
                            "detect" -> ComputeAccelerationDetector.detect(useGpu).let { info -> JSONObject()
                                .put("devices", JSONArray().apply { info.devices.forEach { device -> put(JSONObject()
                                    .put("name", device.name).put("backend", device.backend).put("description", device.description)) } })
                                .put("nnapi", JSONArray(info.nnapiAccelerators)).put("opencl", info.openClDriverDetected)
                                .put("available", info.nativeRuntimeAvailable) }
                            "whisper" -> {
                                val cues = whisper.transcribe(request.getString("modelId"), File(request.getString("audioPath")),
                                    request.getString("language"), request.optBoolean("fastDecoding"), useGpu,
                                    requestedThreads = threads, threadPriority = priority, onProgress = ::progress)
                                JSONObject().put("segments", JSONArray().apply { cues.forEach { cue -> put(JSONObject()
                                    .put("startMs", cue.startMs).put("endMs", cue.endMs).put("text", cue.text)) } })
                            }
                            "generate" -> JSONObject().put("text", local.generate(request.getString("modelPath"),
                                request.getString("system"), request.getString("user"),
                                request.optString("grammar").takeIf(String::isNotBlank), useGpu,
                                threads, batchTokens, priority, ::progress))
                            else -> throw IOException("Operasi native tidak dikenal")
                        }
                        resultFile.writeText(result.toString(), Charsets.UTF_8)
                    } catch (error: Throwable) {
                        AppDiagnostics.recordFailure("inference.request_failed", error)
                        failure = error.message?.take(500) ?: "Proses native gagal; coba model lebih kecil"
                    } finally {
                        synchronized(monitor) { job = null }
                    }
                    // Clear the busy state before a client can submit the next cue group.
                    runCatching { if (failure == null) callback.onCompleted() else callback.onError(failure) }
                }.also { it.start() }
            }
        }

        override fun cancel() { Process.killProcess(Process.myPid()) }
    }

    private fun checkedFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == File(cacheDir, "native-inference").canonicalFile) { "Lokasi data native tidak valid" }
        return file
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // Native model/driver memory is released when the session unbinds.
        if (currentService.compareAndSet(this, null)) Process.killProcess(Process.myPid())
    }

    private companion object { val currentService = AtomicReference<NativeInferenceService?>() }
}
