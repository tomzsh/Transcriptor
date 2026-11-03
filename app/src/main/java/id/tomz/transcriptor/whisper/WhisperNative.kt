package id.tomz.transcriptor.whisper

import androidx.annotation.Keep

@Keep
data class WhisperOutputSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Keep
fun interface WhisperProgressListener {
    fun onProgress(percent: Int)
}

@Keep
object WhisperNative {
    init {
        System.loadLibrary("transcriptor_whisper")
    }

    external fun createContext(modelPath: String, useGpu: Boolean = true): Long
    external fun freeContext(contextHandle: Long)
    external fun createCancellationFlag(): Long
    external fun cancel(cancellationHandle: Long)
    external fun freeCancellationFlag(cancellationHandle: Long)
    external fun transcribe(
        contextHandle: Long,
        audioPath: String,
        language: String,
        requestedThreads: Int,
        cancellationHandle: Long,
        fastDecoding: Boolean = false,
        progressListener: WhisperProgressListener,
    ): Array<WhisperOutputSegment>
}
