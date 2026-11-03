package id.tomz.transcriptor.whisper

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import id.tomz.transcriptor.model.VerifiedModelInstaller
import id.tomz.transcriptor.model.localModelDirectory
import id.tomz.transcriptor.domain.TranscriptionLanguages
import id.tomz.transcriptor.domain.languageOption
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class WhisperTranscriber @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val modelDirectory = localModelDirectory(context)
    private val cancellationLock = Any()

    @Volatile
    private var activeCancellationHandle = 0L

    /**
     * [fastDecoding] uses fewer candidates for uncertain-speech fallback and may
     * reduce accuracy. The default retains Whisper's standard decoding settings.
     */
    suspend fun transcribe(
        modelId: String,
        normalizedAudio: File,
        language: String,
        fastDecoding: Boolean = false,
        useGpu: Boolean = true,
        requestedThreads: Int = 3,
        threadPriority: Int = 5,
        onProgress: (Int) -> Unit,
    ): List<WhisperOutputSegment> = withContext(Dispatchers.IO) {
        val model = ModelCatalog.get(modelId)?.takeIf { it.kind == ModelKind.WHISPER }
            ?: throw IOException("Model Whisper tidak dikenal")
        if (model.englishOnly && language !in setOf("auto", "en")) {
            throw IOException("Model English-only hanya mendukung transkrip berbahasa Inggris")
        }
        if (language != "auto" && !TranscriptionLanguages.supports(language)) {
            throw IOException("Bahasa transkripsi tidak didukung")
        }
        val modelLanguage = TranscriptionLanguages.forModel(language, model.englishOnly, model.transcriptionLanguage)
        if (model.transcriptionLanguage != null && language != "auto" && language != modelLanguage) {
            throw IOException("Model ini hanya mendukung transkripsi bahasa ${languageOption(modelLanguage).label}")
        }
        val modelFile = File(modelDirectory, model.fileName)
        if (!VerifiedModelInstaller.matches(modelFile, model.bytes, model.sha256)) {
            throw IOException("Model belum terpasang atau verifikasinya gagal")
        }
        if (!normalizedAudio.isFile || normalizedAudio.length() < Float.SIZE_BYTES) {
            throw IOException("Audio normalisasi tidak tersedia")
        }

        val cancellationHandle = WhisperNative.createCancellationFlag()
        if (cancellationHandle == 0L) throw IOException("Tidak dapat menyiapkan pembatalan transkripsi")
        synchronized(cancellationLock) {
            if (activeCancellationHandle != 0L) {
                WhisperNative.freeCancellationFlag(cancellationHandle)
                throw IllegalStateException("Transkripsi lain masih berjalan")
            }
            activeCancellationHandle = cancellationHandle
        }

        var contextHandle = 0L
        val tid = android.os.Process.myTid()
        val previousPriority = android.os.Process.getThreadPriority(tid)
        try {
            android.os.Process.setThreadPriority(threadPriority.coerceIn(0, 10))
            currentCoroutineContext().ensureActive()
            contextHandle = WhisperNative.createContext(modelFile.absolutePath, useGpu)
            currentCoroutineContext().ensureActive()
            val segments = WhisperNative.transcribe(
                contextHandle = contextHandle,
                audioPath = normalizedAudio.absolutePath,
                language = modelLanguage,
                requestedThreads = requestedThreads.coerceIn(1, 4),
                cancellationHandle = cancellationHandle,
                fastDecoding = fastDecoding,
                progressListener = WhisperProgressListener { percent -> onProgress(percent.coerceIn(0, 100)) },
            )
            currentCoroutineContext().ensureActive()
            segments.asList()
        } finally {
            if (contextHandle != 0L) WhisperNative.freeContext(contextHandle)
            synchronized(cancellationLock) {
                if (activeCancellationHandle == cancellationHandle) activeCancellationHandle = 0L
                WhisperNative.freeCancellationFlag(cancellationHandle)
            }
            android.os.Process.setThreadPriority(previousPriority)
        }
    }

    fun cancel() {
        synchronized(cancellationLock) {
            if (activeCancellationHandle != 0L) WhisperNative.cancel(activeCancellationHandle)
        }
    }
}
