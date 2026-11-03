package id.tomz.transcriptor.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.SonicAudioProcessor
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.min
import kotlin.math.roundToLong

/** Streams decoder PCM through Media3 Sonic and writes timestamp-aligned mono float32 at 16 kHz. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SonicPcmNormalizer(
    private val inputSampleRate: Int,
    private val channelCount: Int,
    private val output: OutputStream,
) {

    init {
        require(inputSampleRate > 0) { "Input sample rate must be positive" }
        require(channelCount > 0) { "Channel count must be positive" }
    }
    private val frameBytes = channelCount * Short.SIZE_BYTES
    private val outputBytes = ByteArray(OUTPUT_BUFFER_BYTES)
    private val sonic = if (inputSampleRate == TARGET_SAMPLE_RATE) null else SonicAudioProcessor().apply {
        setOutputSampleRateHz(TARGET_SAMPLE_RATE)
        configure(AudioProcessor.AudioFormat(inputSampleRate, channelCount, C.ENCODING_PCM_16BIT))
        flush(StreamMetadata.DEFAULT)
    }
    private var outputBufferSize = 0
    private var outputFramesWritten = 0L
    private var expectedInputFrame: Long? = null
    private var finished = false


    fun writePcm16(pcm: ByteBuffer, presentationTimeUs: Long? = null) {
        check(!finished) { "Audio normalizer is already finished" }
        require(pcm.isDirect) { "PCM input must be a direct buffer" }
        val input = pcm.order(ByteOrder.nativeOrder())
        require(input.remaining() % frameBytes == 0) { "PCM data must contain complete audio frames" }
        val inputFrames = input.remaining() / frameBytes
        if (inputFrames == 0) return

        val expectedFrame = expectedInputFrame
        val startFrame = presentationTimeUs?.takeIf { it >= 0L }?.let(::inputFrameAt)
            ?: expectedFrame
            ?: 0L
        if (expectedFrame == null) {
            writeSilenceUntil(outputFrameAt(startFrame))
        } else if (startFrame > expectedFrame) {
            finishSonicSegment()
            writeSilenceUntil(outputFrameAt(startFrame))
        } else if (startFrame < expectedFrame) {
            val overlapFrames = min(expectedFrame - startFrame, inputFrames.toLong()).toInt()
            input.position(input.position() + overlapFrames * frameBytes)
        }

        if (input.hasRemaining()) {
            if (sonic == null) {
                writeMonoFrames(input)
            } else {
                sonic.queueInput(input)
                drainSonicOutput()
            }
        }
        expectedInputFrame = maxOf(expectedFrame ?: startFrame, startFrame + inputFrames)
    }

    fun finish() {
        if (finished) return
        finishSonicSegment()
        flushOutputBuffer()
        output.flush()
        finished = true
    }

    private fun finishSonicSegment() {
        val processor = sonic ?: return
        processor.queueEndOfStream()
        while (!processor.isEnded()) {
            val processed = processor.output.order(ByteOrder.nativeOrder())
            if (processed.hasRemaining()) writeMonoFrames(processed)
        }
        processor.flush(StreamMetadata.DEFAULT)
    }

    private fun drainSonicOutput() {
        val processor = checkNotNull(sonic)
        while (true) {
            val processed = processor.output.order(ByteOrder.nativeOrder())
            if (!processed.hasRemaining()) return
            writeMonoFrames(processed)
        }
    }

    private fun writeMonoFrames(pcm: ByteBuffer) {
        require(pcm.remaining() % frameBytes == 0) { "Sonic returned incomplete PCM frames" }
        while (pcm.hasRemaining()) {
            var channelSum = 0
            repeat(channelCount) { channelSum += pcm.short.toInt() }
            writeFloat(channelSum.toFloat() / channelCount / PCM_SCALE)
        }
    }

    private fun writeSilenceUntil(targetOutputFrame: Long) {
        var remainingFrames = (targetOutputFrame - outputFramesWritten).coerceAtLeast(0L)
        while (remainingFrames > 0L) {
            val availableFrames = (outputBytes.size - outputBufferSize) / Float.SIZE_BYTES
            val frameCount = min(remainingFrames, availableFrames.toLong()).toInt()
            val byteCount = frameCount * Float.SIZE_BYTES
            Arrays.fill(outputBytes, outputBufferSize, outputBufferSize + byteCount, 0.toByte())
            outputBufferSize += byteCount
            outputFramesWritten += frameCount
            remainingFrames -= frameCount
            if (outputBufferSize == outputBytes.size) flushOutputBuffer()
        }
    }

    private fun writeFloat(sample: Float) {
        val bits = sample.toRawBits()
        outputBytes[outputBufferSize++] = bits.toByte()
        outputBytes[outputBufferSize++] = (bits ushr 8).toByte()
        outputBytes[outputBufferSize++] = (bits ushr 16).toByte()
        outputBytes[outputBufferSize++] = (bits ushr 24).toByte()
        outputFramesWritten++
        if (outputBufferSize == outputBytes.size) flushOutputBuffer()
    }

    private fun flushOutputBuffer() {
        if (outputBufferSize == 0) return
        output.write(outputBytes, 0, outputBufferSize)
        outputBufferSize = 0
    }

    private fun inputFrameAt(presentationTimeUs: Long): Long =
        (presentationTimeUs.toDouble() * inputSampleRate / MICROSECONDS_PER_SECOND).roundToLong().coerceAtLeast(0L)

    private fun outputFrameAt(inputFrame: Long): Long =
        (inputFrame.toDouble() * TARGET_SAMPLE_RATE / inputSampleRate).roundToLong().coerceAtLeast(0L)

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val PCM_SCALE = 32_768f
        const val OUTPUT_BUFFER_BYTES = 16 * 1024
    }
}
