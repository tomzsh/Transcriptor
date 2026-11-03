package id.tomz.transcriptor.speaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class SherpaDiarizationWorker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var activeWorker: IDiarizationService? = null

    @Volatile
    private var activeContinuation: CancellableContinuation<List<SpeakerTurn>>? = null

    suspend fun diarize(
        normalizedAudio: File,
        segmentationModel: File,
        embeddingModel: File,
        resultFile: File,
        onProgress: (Int) -> Unit,
    ): List<SpeakerTurn> = withContext(Dispatchers.IO) {
        val bound = bindWorker()
        activeWorker = bound.worker
        try {
            currentCoroutineContext().ensureActive()
            suspendCancellableCoroutine { continuation ->
                activeContinuation = continuation
                val terminal = AtomicBoolean(false)
                var deathRecipient: IBinder.DeathRecipient? = null

                fun unlinkDeathRecipient() {
                    deathRecipient?.let { recipient ->
                        runCatching { bound.binder.unlinkToDeath(recipient, 0) }
                    }
                }

                fun fail(error: Throwable) {
                    if (terminal.compareAndSet(false, true)) {
                        unlinkDeathRecipient()
                        continuation.resumeWithException(error)
                    }
                }

                fun complete(callbackPath: String) {
                    if (!terminal.compareAndSet(false, true)) return
                    unlinkDeathRecipient()
                    try {
                        if (callbackPath != resultFile.absolutePath) {
                            throw IOException("Worker mengembalikan lokasi hasil yang tidak sesuai")
                        }
                        continuation.resume(parseTurns(resultFile))
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }

                val recipient = IBinder.DeathRecipient {
                    fail(RemoteException("Proses pengelompokan pembicara berhenti"))
                }
                deathRecipient = recipient
                continuation.invokeOnCancellation {
                    terminal.set(true)
                    unlinkDeathRecipient()
                    runCatching { bound.worker.cancel() }
                    bound.unbind()
                    if (activeWorker === bound.worker) activeWorker = null
                    if (activeContinuation === continuation) activeContinuation = null
                }

                try {
                    bound.binder.linkToDeath(recipient, 0)
                    bound.worker.start(
                        normalizedAudio.absolutePath,
                        segmentationModel.absolutePath,
                        embeddingModel.absolutePath,
                        resultFile.absolutePath,
                        object : IDiarizationCallback.Stub() {
                            override fun onProgress(processed: Int, total: Int) {
                                if (total > 0 && continuation.isActive) {
                                    runCatching { onProgress(diarizationProgressPercent(processed, total)) }
                                }
                            }

                            override fun onCompleted(resultPath: String) = complete(resultPath)

                            override fun onError(message: String) {
                                fail(IOException(message.take(MAX_ERROR_LENGTH)))
                            }
                        },
                    )
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        } catch (error: RemoteException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Proses pengelompokan pembicara berhenti", error)
        } finally {
            if (activeWorker === bound.worker) activeWorker = null
            if (activeContinuation?.isActive == false) activeContinuation = null
            bound.unbind()
        }
    }

    fun cancel() {
        val continuation = activeContinuation
        if (continuation != null) {
            continuation.cancel(CancellationException("Pengelompokan pembicara dibatalkan"))
        } else {
            runCatching { activeWorker?.cancel() }
        }
    }

    private suspend fun bindWorker(): BoundWorker {
        val binder = CompletableDeferred<IBinder>()
        val unbound = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binder.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder.completeExceptionally(RemoteException("Diarization worker disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                binder.completeExceptionally(RemoteException("Diarization worker died"))
            }

            override fun onNullBinding(name: ComponentName) {
                binder.completeExceptionally(RemoteException("Diarization worker returned no binder"))
            }
        }
        if (!appContext.bindService(
                Intent(appContext, SherpaDiarizationWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        ) {
            throw IOException("Tidak dapat memulai proses diarization")
        }
        try {
            val remoteBinder = binder.await()
            val worker = IDiarizationService.Stub.asInterface(remoteBinder)
            return BoundWorker(worker, remoteBinder) {
                if (unbound.compareAndSet(false, true)) runCatching { appContext.unbindService(connection) }
            }
        } catch (error: Throwable) {
            if (unbound.compareAndSet(false, true)) runCatching { appContext.unbindService(connection) }
            throw error
        }
    }

    private fun parseTurns(file: File): List<SpeakerTurn> {
        if (!file.isFile) throw IOException("Berkas hasil diarization tidak tersedia")
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val startMs = item.getLong("startMs")
                val endMs = item.getLong("endMs")
                if (startMs >= 0 && endMs > startMs) {
                    add(SpeakerTurn(startMs, endMs, item.getInt("clusterId")))
                }
            }
        }
    }

    private class BoundWorker(
        val worker: IDiarizationService,
        val binder: IBinder,
        private val release: () -> Unit,
    ) {
        fun unbind() = release()
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}
