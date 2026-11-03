package id.tomz.transcriptor.speaker

import android.content.Context
import android.app.ActivityManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import id.tomz.transcriptor.model.VerifiedModelInstaller
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class SherpaSpeakerDiarizer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun diarize(
        normalizedAudio: File,
        segmentationFile: File,
        embeddingFile: File,
        onProgress: (Int, Int) -> Unit,
    ): List<SpeakerTurn> = withContext(Dispatchers.IO) {
        val segmentation = ModelCatalog.diarizationModels.first { it.kind == ModelKind.DIARIZATION_SEGMENTATION }
        val embedding = ModelCatalog.diarizationModels.first { it.kind == ModelKind.DIARIZATION_EMBEDDING }
        if (!VerifiedModelInstaller.matches(segmentationFile, segmentation.bytes, segmentation.sha256) ||
            !VerifiedModelInstaller.matches(embeddingFile, embedding.bytes, embedding.sha256)
        ) {
            throw IOException("Unduh model diarization sebelum mengelompokkan pembicara")
        }
        val samples = readNormalizedAudio(normalizedAudio)
        val durationMs = (samples.size.toDouble() * 1_000 / TARGET_SAMPLE_RATE).roundToLong()
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentationFile.absolutePath,
                    windowShiftRatio = 0.1f,
                ),
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingFile.absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(threshold = 0.5f, computeConfidence = true),
        )
        val diarization = OfflineSpeakerDiarization(config = config)
        val coroutineContext = currentCoroutineContext()
        try {
            if (diarization.sampleRate() != TARGET_SAMPLE_RATE) {
                throw IOException("Model diarization membutuhkan audio 16 kHz")
            }
            coroutineContext.ensureActive()
            val detected = diarization.processWithCallback(
                samples = samples,
                callback = SherpaProgressCallback(onProgress),
            )
            coroutineContext.ensureActive()
            detected.mapNotNull { segment ->
                speakerTurnFromSeconds(segment.start, segment.end, segment.speaker, durationMs)
            }
        } finally {
            diarization.release()
        }
    }

    private fun readNormalizedAudio(file: File): FloatArray {
        val byteCount = file.length()
        if (!file.isFile || byteCount < Float.SIZE_BYTES || byteCount % Float.SIZE_BYTES != 0L) {
            throw IOException("Audio normalisasi tidak valid untuk diarization")
        }
        val sampleCount = byteCount / Float.SIZE_BYTES
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
            ?: throw IOException("Informasi memori perangkat tidak tersedia")
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        if (!canStartDiarization(byteCount, availableHeapBytes(), memoryInfo.lowMemory)) {
            throw IOException("Audio terlalu panjang untuk mengelompokkan pembicara di memori perangkat")
        }
        val samples = FloatArray(sampleCount.toInt())
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, byteCount)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
            buffer.get(samples)
        }
        return samples
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
    }
}

internal fun canStartDiarization(
    pcmByteCount: Long,
    availableHeapBytes: Long,
    lowMemory: Boolean,
): Boolean {
    val safetyReserve = 128L * 1024L * 1024L
    if (lowMemory || pcmByteCount <= 0L || availableHeapBytes < safetyReserve) return false
    if (pcmByteCount > (Long.MAX_VALUE - safetyReserve) / 2L) return false
    return pcmByteCount * 2L + safetyReserve <= availableHeapBytes
}

private fun availableHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
}

internal class SherpaProgressCallback(
    private val onProgress: (Int, Int) -> Unit,
) : Function3<Int, Int, Long, Int> {
    @Suppress("UNUSED_PARAMETER")
    override fun invoke(processedChunks: Int, totalChunks: Int, argument: Long): Int {
        if (totalChunks > 0) {
            onProgress(processedChunks.coerceIn(0, totalChunks), totalChunks)
        }
        return 0
    }
}

internal fun diarizationProgressPercent(processed: Int, total: Int): Int =
    if (total <= 0) 0 else ((processed.toLong() * 100L) / total).coerceIn(0L, 100L).toInt()


internal fun speakerTurnFromSeconds(
    startSeconds: Float,
    endSeconds: Float,
    clusterId: Int,
    durationMs: Long,
): SpeakerTurn? {
    if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds <= startSeconds || durationMs <= 0L) return null
    val startMs = (startSeconds.toDouble() * 1_000).roundToLong().coerceIn(0L, durationMs)
    val endMs = (endSeconds.toDouble() * 1_000).roundToLong().coerceIn(0L, durationMs)
    return if (endMs > startMs) SpeakerTurn(startMs, endMs, clusterId) else null
}
