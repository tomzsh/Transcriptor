package id.tomz.transcriptor.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Extracts one exact regular-file entry; archive paths are never used as output paths. */
object SecureModelArchive {
    fun extractVerifiedEntry(archive: File, entryName: String, output: File, expectedBytes: Long) {
        if (entryName.startsWith('/') || entryName.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Unsafe model archive entry")
        }
        val parent = output.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create model directory")
        try {
            var found = false
            BZip2CompressorInputStream(archive.inputStream()).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.name != entryName) continue
                        if (!entry.isFile || entry.size != expectedBytes) throw InvalidModelException("Model archive entry has an unexpected type or size")
                        FileOutputStream(output, false).use { destination ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = 0L
                            while (true) {
                                val count = tar.read(buffer)
                                if (count < 0) break
                                written += count
                                if (written > expectedBytes) throw InvalidModelException("Model archive entry exceeds its expected size")
                                destination.write(buffer, 0, count)
                            }
                            if (written != expectedBytes) throw InvalidModelException("Model archive entry is incomplete")
                        }
                        found = true
                        break
                    }
                }
            }
            if (!found) throw InvalidModelException("Verified model archive does not contain the expected model")
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw InvalidModelException("Could not extract verified model archive", error)
        }
    }
}
