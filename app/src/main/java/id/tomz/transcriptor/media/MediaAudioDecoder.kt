package id.tomz.transcriptor.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DecodedAudio(
    val sampleCount: Long,
    val durationMs: Long,
)

/** Decodes the first audio track to mono 16 kHz float32 little-endian using Media3 Sonic. */
internal class MediaAudioDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun decodeToFloat32(
        source: Uri,
        destination: File,
        onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    ): DecodedAudio = withContext(Dispatchers.IO) {
        val cancellationContext = currentCoroutineContext()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        val partial = File(destination.parentFile, "${destination.name}.part")
        try {
            destination.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Could not create audio cache") }
            partial.delete()
            extractor.setDataSource(context, source, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("The selected media has no decodable audio track")
            val sourceFormat = extractor.getTrackFormat(audioTrack)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("The selected audio track has no codec type")
            val durationMs = sourceFormat.longOrNull(MediaFormat.KEY_DURATION)?.div(1_000L) ?: 0L
            extractor.selectTrack(audioTrack)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            codecStarted = true

            var sampleRate = sourceFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var channelCount = sourceFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var normalizer: SonicPcmNormalizer? = null
            var normalizerSampleRate = 0
            var normalizerChannelCount = 0
            var inputEnded = false
            var outputEnded = false
            val bufferInfo = MediaCodec.BufferInfo()

            FileOutputStream(partial).use { normalizedOutput ->
                while (!outputEnded) {
                    cancellationContext.ensureActive()
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: throw IOException("Audio decoder returned an empty input buffer")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                                    throw IOException("Encrypted media cannot be transcribed offline")
                                }
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            channelCount = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                            pcmEncoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                                ?: AudioFormat.ENCODING_PCM_16BIT
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            try {
                                if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
                                        throw IOException("This device returned unsupported decoded PCM format $pcmEncoding")
                                    }
                                    if (sampleRate <= 0 || channelCount <= 0) {
                                        throw IOException("Audio decoder returned an invalid sample format")
                                    }
                                    val pcm = decoder.getOutputBuffer(outputIndex)
                                        ?: throw IOException("Audio decoder returned an empty output buffer")
                                    pcm.position(bufferInfo.offset)
                                    pcm.limit(bufferInfo.offset + bufferInfo.size)
                                    val currentNormalizer = normalizer ?: SonicPcmNormalizer(
                                        sampleRate,
                                        channelCount,
                                        normalizedOutput,
                                    ).also {
                                        normalizerSampleRate = sampleRate
                                        normalizerChannelCount = channelCount
                                        normalizer = it
                                    }
                                    if (sampleRate != normalizerSampleRate || channelCount != normalizerChannelCount) {
                                        throw IOException("Decoded audio format changed during the stream")
                                    }
                                    currentNormalizer.writePcm16(pcm, bufferInfo.presentationTimeUs)
                                    onProgress((bufferInfo.presentationTimeUs / 1_000L).coerceAtLeast(0L), durationMs)
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                            } finally {
                                decoder.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
                normalizer?.finish()
                normalizedOutput.fd.sync()
            }

            if (normalizer == null || partial.length() == 0L) throw IOException("The selected media contains no decoded audio")
            if (destination.exists() && !destination.delete()) throw IOException("Could not replace normalized audio")
            if (!partial.renameTo(destination)) throw IOException("Could not commit normalized audio")
            DecodedAudio(destination.length() / Float.SIZE_BYTES, durationMs)
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
}
