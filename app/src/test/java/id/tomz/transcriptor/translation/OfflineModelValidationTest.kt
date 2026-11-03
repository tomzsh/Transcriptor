package id.tomz.transcriptor.translation

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineModelValidationTest {
    @Test fun acceptsGgufVersionsTwoAndThree() {
        for (version in listOf(2, 3)) withModel(version = version) { file ->
            validateOfflineGguf(file)
            assertEquals(MIN_OFFLINE_MODEL_BYTES, file.length())
        }
    }

    @Test fun rejectsWrongMagicAndUnsupportedVersions() {
        withModel(magic = "GGML") { assertThrows(IllegalArgumentException::class.java) { validateOfflineGguf(it) } }
        withModel(version = 99) { assertThrows(IllegalArgumentException::class.java) { validateOfflineGguf(it) } }
    }

    @Test fun rejectsTruncatedAndOversizedFiles() {
        withModel(size = 4) { assertThrows(IllegalArgumentException::class.java) { validateOfflineGguf(it) } }
        withModel(size = MAX_OFFLINE_MODEL_BYTES + 1) {
            assertThrows(IllegalArgumentException::class.java) { validateOfflineGguf(it) }
        }
    }

    @Test fun acceptsGemma4SizedGguf() {
        withModel(size = 3_349_516_256L) { file ->
            validateOfflineGguf(file)
            assertEquals(3_349_516_256L, file.length())
        }
    }

    private fun withModel(
        version: Int = 3,
        magic: String = "GGUF",
        size: Long = MIN_OFFLINE_MODEL_BYTES,
        block: (File) -> Unit,
    ) {
        val file = File.createTempFile("offline-model-test", ".gguf")
        try {
            RandomAccessFile(file, "rw").use {
                it.write(magic.toByteArray(Charsets.US_ASCII))
                it.write(byteArrayOf(version.toByte(), 0, 0, 0))
                it.setLength(size)
            }
            block(file)
        } finally {
            file.delete()
        }
    }
}
