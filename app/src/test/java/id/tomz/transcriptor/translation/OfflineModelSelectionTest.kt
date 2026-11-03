package id.tomz.transcriptor.translation

import id.tomz.transcriptor.model.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class OfflineModelSelectionTest {
    @Test fun missingExplicitChoiceCannotFallBackToAnotherModelOrImport() {
        val root = Files.createTempDirectory("ai-selection").toFile()
        try {
            val imported = fixture(root, "imported.gguf")
            val catalog = fixture(root, "catalog.gguf")
            val spec = ModelSpec("ai", "AI", catalog.name, null, "https://example.com/model", catalog.length(), "unused", false, "MIT", ModelKind.AI_TRANSLATION)
            assertEquals(imported, offlineModelCandidate(IMPORTED_MODEL_ID, imported, root, listOf(spec)))
            assertEquals(catalog, offlineModelCandidate("ai", imported, root, listOf(spec)))
            assertNull(offlineModelCandidate(null, imported, root, listOf(spec)))
            assertNull(offlineModelCandidate("unknown", imported, root, listOf(spec)))
            catalog.delete()
            assertNull(offlineModelCandidate("ai", imported, root, listOf(spec)))
        } finally { root.deleteRecursively() }
    }

    @Test fun incompleteCatalogAndWhisperFilesCannotBecomeOfflineAi() {
        val root = Files.createTempDirectory("ai-selection-invalid").toFile()
        try {
            val file = fixture(root, "catalog.gguf")
            val spec = ModelSpec("ai", "AI", file.name, null, "https://example.com/model", file.length() + 1, "unused", false, "MIT", ModelKind.AI_TRANSLATION)
            assertNull(offlineModelCandidate("ai", File(root, "missing"), root, listOf(spec)))
            assertNull(offlineModelCandidate("ai", file, root, listOf(spec.copy(bytes = file.length(), kind = ModelKind.WHISPER))))
            file.writeText("not a GGUF")
            assertNull(offlineModelCandidate(IMPORTED_MODEL_ID, file, root, listOf(spec)))
        } finally { root.deleteRecursively() }
    }

    private fun fixture(root: File, name: String): File = File(root, name).also { file ->
        RandomAccessFile(file, "rw").use {
            it.setLength(MIN_OFFLINE_MODEL_BYTES)
            it.write(byteArrayOf(0x47, 0x47, 0x55, 0x46, 3, 0, 0, 0))
        }
    }
}
