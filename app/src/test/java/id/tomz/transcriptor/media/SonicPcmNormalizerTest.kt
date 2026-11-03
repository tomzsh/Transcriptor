package id.tomz.transcriptor.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SonicPcmNormalizerTest {
    @Test
    fun resamplesStereoToMonoAtSixteenKilohertz() {
        val output = ByteArrayOutputStream()
        val normalizer = SonicPcmNormalizer(48_000, 2, output)
        val pcm = ByteBuffer.allocateDirect(48_000 * 2 * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        repeat(48_000) {
            pcm.putShort(1_000)
            pcm.putShort(3_000)
        }
        pcm.flip()

        normalizer.writePcm16(pcm)
        normalizer.finish()

        val samples = output.toFloatArray()
        assertEquals(16_000, samples.size)
        assertEquals(2_000f / 32_768f, samples[1_000], 0.002f)
    }

    @Test
    fun preservesInitialPresentationOffsetAndGapsAsSilence() {
        val output = ByteArrayOutputStream()
        val normalizer = SonicPcmNormalizer(16_000, 1, output)

        normalizer.writePcm16(monoFrames(shortArrayOf(100, 200)), presentationTimeUs = 100_000)
        normalizer.writePcm16(monoFrames(shortArrayOf(300, 400)), presentationTimeUs = 200_000)
        normalizer.finish()

        val samples = output.toFloatArray()
        assertEquals(3_202, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(100f / 32_768, samples[1_600], 0.000001f)
        assertEquals(0f, samples[1_602], 0f)
        assertEquals(0f, samples[3_199], 0f)
        assertEquals(300f / 32_768, samples[3_200], 0.000001f)
    }

    @Test
    fun trimsOverlappingPresentationFrames() {
        val output = ByteArrayOutputStream()
        val normalizer = SonicPcmNormalizer(16_000, 1, output)

        normalizer.writePcm16(monoFrames(shortArrayOf(10, 20, 30, 40)), presentationTimeUs = 0)
        normalizer.writePcm16(monoFrames(shortArrayOf(30, 40, 50)), presentationTimeUs = 125)
        normalizer.finish()

        assertEquals(
            listOf(10f, 20f, 30f, 40f, 50f).map { it / 32_768 },
            output.toFloatArray().toList(),
        )
    }

    @Test
    fun rejectsPartialPcmFrames() {
        val normalizer = SonicPcmNormalizer(16_000, 2, ByteArrayOutputStream())
        val incompleteStereoFrame = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
            .put(byteArrayOf(0, 0, 1))
            .flip() as ByteBuffer

        assertThrows(IllegalArgumentException::class.java) { normalizer.writePcm16(incompleteStereoFrame) }
    }

    private fun monoFrames(values: ShortArray): ByteBuffer = ByteBuffer.allocateDirect(values.size * 2)
        .order(ByteOrder.nativeOrder())
        .apply { values.forEach(::putShort); flip() }

    private fun ByteArrayOutputStream.toFloatArray(): FloatArray {
        val bytes = toByteArray()
        assertEquals(0, bytes.size % Float.SIZE_BYTES)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .let { buffer -> FloatArray(buffer.remaining(), buffer::get) }
    }
}
