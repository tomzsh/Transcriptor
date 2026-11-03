package id.tomz.transcriptor.model

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ModelKind { WHISPER, DIARIZATION_SEGMENTATION, DIARIZATION_EMBEDDING, AI_TRANSLATION }

data class ModelSpec(
    val id: String,
    val label: String,
    val fileName: String,
    val mirrorUrl: String?,
    val upstreamUrl: String,
    val bytes: Long,
    val sha256: String,
    val englishOnly: Boolean,
    val licence: String,
    val kind: ModelKind,
    val downloadBytes: Long = bytes,
    val downloadSha256: String = sha256,
    val archiveMember: String? = null,
    val description: String = "",
    val licenceUrl: String? = null,
    val transcriptionLanguage: String? = null,
)

class InvalidModelException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Installs only a complete, size- and SHA-256-verified download. */
object VerifiedModelInstaller {
    fun matches(file: File, expectedBytes: Long, expectedSha256: String): Boolean =
        file.isFile && file.length() == expectedBytes &&
            runCatching { sha256(file).equals(expectedSha256, ignoreCase = true) }.getOrDefault(false)

    fun verify(partial: File, expectedBytes: Long, expectedSha256: String) {
        if (!partial.isFile) throw InvalidModelException("Downloaded model file is missing")
        if (partial.length() != expectedBytes) {
            partial.delete()
            throw InvalidModelException("Model size does not match the catalogue")
        }
        val digest = try {
            sha256(partial)
        } catch (error: Exception) {
            throw InvalidModelException("Could not verify downloaded model", error)
        }
        if (!digest.equals(expectedSha256, ignoreCase = true)) {
            partial.delete()
            throw InvalidModelException("Model SHA-256 does not match the catalogue")
        }
    }

    fun install(partial: File, destination: File, spec: ModelSpec) {
        verify(partial, spec.bytes, spec.sha256)
        val parent = destination.absoluteFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create model directory")
        }
        try {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String =
        Files.newInputStream(file.toPath()).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
