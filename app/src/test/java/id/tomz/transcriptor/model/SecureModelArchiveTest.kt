package id.tomz.transcriptor.model

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecureModelArchiveTest {
    @Test fun extractsOnlyThePinnedRegularFileEntry() {
        val expected = "int8 model".toByteArray()
        val directory = Files.createTempDirectory("model-archive").toFile()
        val escapeSentinel = checkNotNull(directory.parentFile).resolve("outside-${directory.name}.bin")
        val archive = directory.resolve("model.tar.bz2")
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            add(tar, "../../${escapeSentinel.name}", "ignored".toByteArray())
            add(tar, "models/segmentation.int8.onnx", expected)
        }
        archive.writeBytes(bytes.toByteArray())
        val extracted = directory.resolve("extract.part")
        try {
            SecureModelArchive.extractVerifiedEntry(archive, "models/segmentation.int8.onnx", extracted, expected.size.toLong())
            assertArrayEquals(expected, extracted.readBytes())
            assertFalse(escapeSentinel.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun add(tar: TarArchiveOutputStream, name: String, bytes: ByteArray) {
        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        tar.write(bytes)
        tar.closeArchiveEntry()
    }
}
