package id.tomz.transcriptor.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import id.tomz.transcriptor.diagnostics.AppDiagnostics
import id.tomz.transcriptor.R
import dagger.hilt.android.qualifiers.ApplicationContext
import id.tomz.transcriptor.translation.ComputeAccelerationInfo
import id.tomz.transcriptor.translation.ComputeBackendDevice
import id.tomz.transcriptor.whisper.WhisperOutputSegment
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject

/** Native driver faults terminate a worker, while projects and UI remain in the main process. */
@Singleton
class NativeInferenceWorker @Inject constructor(@ApplicationContext context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var active: BoundWorker? = null

    suspend fun <T> withSession(block: suspend (Session) -> T): T = mutex.withLock {
        val session = Session()
        try { block(session) } finally { session.close() }
    }

    suspend fun transcribe(modelId: String, normalizedAudio: File, language: String,
                          fastDecoding: Boolean = false, onProgress: (Int) -> Unit): List<WhisperOutputSegment> =
        withSession { session ->
            val result = session.execute(JSONObject().put("operation", "whisper")
                .put("modelId", modelId).put("audioPath", normalizedAudio.absolutePath)
                .put("language", language).put("fastDecoding", fastDecoding), onProgress)
            val cues = result.getJSONArray("segments")
            List(cues.length()) { index -> cues.getJSONObject(index).let {
                WhisperOutputSegment(it.getLong("startMs"), it.getLong("endMs"), it.getString("text"))
            } }
        }

    internal suspend fun detect(): ComputeAccelerationInfo = withSession { session ->
        val result = session.execute(JSONObject().put("operation", "detect")) {}
        val devices = result.getJSONArray("devices")
        val nnapi = result.getJSONArray("nnapi")
        ComputeAccelerationInfo(List(devices.length()) { index -> devices.getJSONObject(index).let {
            ComputeBackendDevice(it.getString("name"), it.getString("backend"), it.getString("description"))
        } }, List(nnapi.length()) { nnapi.getString(it) }, result.getBoolean("opencl"), result.getBoolean("available"))
    }

    fun cancel() { runCatching { active?.worker?.cancel() } }

    inner class Session internal constructor() {
        private var bound: BoundWorker? = null
        private var useGpu = true
        private val mode = PerformanceSettings(context).read()

        suspend fun generate(modelPath: String, system: String, user: String, grammar: String? = null,
                             onToken: (Int) -> Unit): String = execute(JSONObject().put("operation", "generate")
            .put("modelPath", modelPath).put("system", system).put("user", user)
            .put("grammar", grammar ?: ""), onToken).getString("text")

        internal suspend fun execute(request: JSONObject, progress: (Int) -> Unit): JSONObject = withContext(Dispatchers.IO) {
            var lastProgress = -1
            while (true) {
                currentCoroutineContext().ensureActive()
                val folder = File(context.cacheDir, "native-inference").apply { mkdirs() }
                val id = UUID.randomUUID().toString()
                val input = File(folder, "$id.request.json")
                val output = File(folder, "$id.result.json")
                val worker = bound ?: bindWorker().also { bound = it; active = it }
                val policy = PerformancePolicy.current(context, mode)
                val started = SystemClock.elapsedRealtime()
                try {
                    input.writeText(request.put("useGpu", useGpu).put("threads", policy.threads)
                        .put("batchTokens", policy.batchTokens).put("priority", policy.priority).toString(), Charsets.UTF_8)
                    AppDiagnostics.event("inference.request_started", mapOf("backend" to if (useGpu) "auto" else "cpu",
                        "threads" to policy.threads, "thermal_status" to policy.thermalStatus))
                    awaitResult(worker, input, output) { value ->
                        synchronized(this@Session) {
                            if (value > lastProgress) { lastProgress = value; progress(value) }
                        }
                    }
                    if (!output.isFile || output.length() > 16L * 1024 * 1024) throw IOException("Hasil proses native tidak valid atau terlalu besar")
                    AppDiagnostics.event("inference.request_completed", mapOf("duration_ms" to SystemClock.elapsedRealtime() - started))
                    return@withContext JSONObject(output.readText(Charsets.UTF_8))
                } catch (error: RemoteException) {
                    AppDiagnostics.recordFailure("inference.worker_died", error)
                    currentCoroutineContext().ensureActive()
                    worker.unbind()
                    bound = null
                    if (active === worker) active = null
                    if (!useGpu) {
                        throw IOException(context.getString(R.string.inference_process_stopped), error)
                    }
                    // A native abort cannot be caught in JNI. Retry this request in a fresh CPU process.
                    android.util.Log.w("NativeInferenceWorker", "Native worker stopped; retrying the request on CPU")
                    useGpu = false
                } catch (error: Exception) {
                    AppDiagnostics.recordFailure("inference.request_failed", error)
                    throw error
                } finally {
                    input.delete(); output.delete()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

        internal suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
            bound?.let { worker ->
                // Unbinding alone tears down asynchronously and can race the next session.
                // Explicitly end this process and observe its death before releasing the lease.
                val died = CompletableDeferred<Unit>()
                val death = IBinder.DeathRecipient { died.complete(Unit) }
                try {
                    try { worker.binder.linkToDeath(death, 0) }
                    catch (_: RemoteException) { died.complete(Unit) }
                    runCatching { worker.worker.cancel() }
                    val stopped = withTimeoutOrNull(5_000) { died.await(); true } ?: false
                    AppDiagnostics.event("inference.session_released", mapOf("success" to stopped))
                } finally {
                    runCatching { worker.binder.unlinkToDeath(death, 0) }
                    worker.unbind()
                    if (active === worker) active = null
                }
            }
            bound = null
        }
    }

    private suspend fun awaitResult(bound: BoundWorker, input: File, output: File, progress: (Int) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val finished = AtomicBoolean(false)
            lateinit var death: IBinder.DeathRecipient
            fun finish(error: Throwable? = null) {
                if (!finished.compareAndSet(false, true)) return
                runCatching { bound.binder.unlinkToDeath(death, 0) }
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
            }
            death = IBinder.DeathRecipient { finish(RemoteException("Native inference worker died")) }
            continuation.invokeOnCancellation {
                finished.set(true)
                runCatching { bound.binder.unlinkToDeath(death, 0) }
                runCatching { bound.worker.cancel() }
            }
            try {
                bound.binder.linkToDeath(death, 0)
                if (continuation.isActive) bound.worker.execute(input.absolutePath, output.absolutePath,
                    object : IInferenceCallback.Stub() {
                        override fun onProgress(value: Int) {
                            if (!finished.get() && continuation.isActive) runCatching { progress(value) }
                        }
                        override fun onCompleted() = finish()
                        override fun onError(message: String) = finish(IOException(message.take(500)))
                    })
            } catch (error: Throwable) { finish(error) }
        }
    }

    private suspend fun bindWorker(): BoundWorker {
        val result = CompletableDeferred<IBinder>()
        val released = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { result.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { result.completeExceptionally(RemoteException("Worker disconnected")) }
            override fun onBindingDied(name: ComponentName) { result.completeExceptionally(RemoteException("Worker binding died")) }
            override fun onNullBinding(name: ComponentName) { result.completeExceptionally(IOException("Worker tidak tersedia")) }
        }
        fun unbind() { if (released.compareAndSet(false, true)) runCatching { context.unbindService(connection) } }
        if (!context.bindService(Intent(context, NativeInferenceService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            throw IOException("Tidak dapat memulai proses AI")
        }
        try {
            val binder = withTimeoutOrNull(20_000) { result.await() } ?: throw IOException("Proses AI tidak merespons saat dimulai")
            return BoundWorker(IInferenceService.Stub.asInterface(binder), binder, ::unbind)
        } catch (error: Throwable) { unbind(); throw error }
    }

    private class BoundWorker(val worker: IInferenceService, val binder: IBinder, val unbind: () -> Unit)
}
