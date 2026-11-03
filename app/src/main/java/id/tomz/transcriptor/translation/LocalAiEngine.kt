package id.tomz.transcriptor.translation

import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Keep
internal fun interface LocalTokenListener {
    fun onToken(count: Int)
}

@Keep
internal object LlamaNative {
    init { System.loadLibrary("transcriptor_llama") }

    external fun createCancellationFlag(): Long
    external fun cancel(cancellationHandle: Long)
    external fun freeCancellationFlag(cancellationHandle: Long)
    external fun getAvailableBackends(): Array<String>
    external fun getNnapiAccelerators(): Array<String>
    external fun isOpenClAvailable(): Boolean
    external fun generate(
        modelPath: ByteArray,
        system: ByteArray,
        user: ByteArray,
        grammar: ByteArray,
        preferredBackend: ByteArray,
        preferredDevice: ByteArray,
        cancellationHandle: Long,
        threads: Int,
        batchTokens: Int,
        listener: LocalTokenListener,
    ): ByteArray
}

@Singleton
class LocalAiEngine @Inject constructor() {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "offline-ai").apply { isDaemon = true } }
    private val activeRequest = AtomicReference<NativeRequest?>()

    suspend fun generate(modelPath: String, system: String, user: String, grammar: String? = null, useGpu: Boolean = true,
                         threads: Int = 3, batchTokens: Int = 64, priority: Int = 5, onToken: (Int) -> Unit): String {
        require(system.isNotBlank() && user.isNotBlank()) { "Prompt AI tidak boleh kosong" }
        require((grammar?.length ?: 0) <= 32_768) { "Format AI terlalu panjang" }
        require(system.length + user.length <= 32_768) { "Teks terlalu panjang untuk model AI offline" }
        return suspendCancellableCoroutine { continuation ->
            val request = NativeRequest()
            continuation.invokeOnCancellation { request.cancel() }
            executor.execute {
                android.os.Process.setThreadPriority(priority.coerceIn(0, 10))
                OfflineAiAccess.lock.lock()
                try {
                    if (!continuation.isActive) return@execute
                    activeRequest.set(request)
                    validateOfflineGguf(File(modelPath))
                    val accelerator = if (useGpu) ComputeAccelerationDetector.chooseTranslationBackend() else null
                    val flag = LlamaNative.createCancellationFlag()
                    check(flag != 0L) { "Memori tidak cukup untuk memulai AI offline" }
                    request.attach(flag)
                    val output = LlamaNative.generate(
                        modelPath.toByteArray(Charsets.UTF_8), system.toByteArray(Charsets.UTF_8),
                        user.toByteArray(Charsets.UTF_8), grammar?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(),
                        accelerator?.backend?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(),
                        accelerator?.name?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(), flag, threads.coerceIn(1, 4), batchTokens.coerceIn(16, 128),
                        LocalTokenListener { count -> if (continuation.isActive) onToken(count) },
                    )
                    if (request.isCancelled) throw CancellationException("AI offline dibatalkan")
                    val result = output.toString(Charsets.UTF_8).trim()
                    check(result.isNotBlank()) { "Model AI menghasilkan jawaban kosong" }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Throwable) {
                    val failure = if (request.isCancelled) CancellationException("AI offline dibatalkan", error) else error
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally {
                    request.dispose()
                    activeRequest.compareAndSet(request, null)
                    OfflineAiAccess.lock.unlock()
                }
            }
        }
    }

    fun cancel() { activeRequest.get()?.cancel() }

    // Cancellation may run on any thread, including while JNI is loading a model. This
    // monitor prevents freeing a flag while a cancellation callback still accesses it.
    private class NativeRequest {
        private var handle = 0L
        @Volatile var isCancelled = false
            private set

        @Synchronized fun attach(value: Long) {
            handle = value
            if (isCancelled) LlamaNative.cancel(value)
        }

        @Synchronized fun cancel() {
            isCancelled = true
            if (handle != 0L) LlamaNative.cancel(handle)
        }

        @Synchronized fun dispose() {
            if (handle != 0L) LlamaNative.freeCancellationFlag(handle)
            handle = 0L
        }
    }
}
