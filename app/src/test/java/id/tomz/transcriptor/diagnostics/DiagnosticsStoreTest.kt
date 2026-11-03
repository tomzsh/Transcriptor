package id.tomz.transcriptor.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsStoreTest {
    @Test fun rotationKeepsRecentCompleteRecordsWithinBoundedFiles() = withFiles { root, _ ->
        val store = DiagnosticsStore(root, "inference", maxFileBytes = 128)
        repeat(40) { store.append("{\"index\":$it}", 1_000L) }
        val files = store.logFiles()
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= 128 })
        assertTrue(files.any { it.readText().contains("\"index\":39") })
        assertFalse(files.any { it.readText().contains("\"index\":0}") })
        assertTrue(files.all { it.readText().endsWith("\n") })
    }

    @Test fun crashRecordIsIndependentOfNormalWriterAndReplacesEarlierCrash() = withFiles { root, _ ->
        val store = DiagnosticsStore(root, "speaker", maxFileBytes = 128)
        store.append("{\"event\":\"start\"}", 1_000L)
        store.writeCrash("{\"event\":\"old\"}")
        store.writeCrash("{\"event\":\"uncaught_exception\"}")
        val crash = store.logFiles().single { it.name.contains("crash") }.readText()
        assertTrue(crash.contains("uncaught_exception"))
        assertFalse(crash.contains("old"))
        assertTrue(store.logFiles().any { it.readText().contains("start") })
    }

    @Test fun exportIncludesEveryProcessButExcludesOtherFilesAndIncompleteLines() = withFiles { root, exports ->
        val main = DiagnosticsStore(root, "main")
        main.append("{\"event\":\"main\"}", System.currentTimeMillis())
        DiagnosticsStore(root, "inference").append("{\"event\":\"native\"}", System.currentTimeMillis())
        File(root, "private.txt").writeText("transcript sk-secret")
        File(root, "speaker.current.jsonl").writeText("{\"event\":\"complete\"}\n{\"event\":\"partial")
        val archive = main.export(exports, "{\"schema\":1}")
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("metadata.json"))
            assertTrue(names.contains("logs/main.current.jsonl"))
            assertTrue(names.contains("logs/inference.current.jsonl"))
            assertFalse(names.contains("private.txt"))
            val speaker = zip.getInputStream(zip.getEntry("logs/speaker.current.jsonl")).bufferedReader().readText()
            assertEquals("{\"event\":\"complete\"}\n", speaker)
        }
    }

    @Test fun clearRemovesAllProcessLogsAndExportsAndAdvancesHistoryCursor() = withFiles { root, exports ->
        val store = DiagnosticsStore(root, "main")
        store.append("{}", System.currentTimeMillis())
        DiagnosticsStore(root, "inference").writeCrash("{}")
        store.export(exports, "{}")
        val clearedAt = System.currentTimeMillis()
        store.clear(exports, clearedAt)
        assertTrue(store.logFiles().isEmpty())
        assertTrue(exports.listFiles().orEmpty().isEmpty())
        assertEquals(clearedAt, store.historyCursor())
    }

    @Test fun expiredLogsAreRemovedWithoutDeletingUnrelatedFiles() = withFiles { root, _ ->
        val store = DiagnosticsStore(root, "main", retentionMs = 100)
        store.append("{}", 1_000L)
        val old = store.logFiles().single().apply { setLastModified(100) }
        val unrelated = File(root, "unrelated.txt").apply { writeText("kept") }
        store.prune(1_000)
        assertFalse(old.exists())
        assertTrue(unrelated.exists())
    }

    private fun withFiles(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("diagnostics-test").toFile()
        try { block(File(directory, "logs"), File(directory, "exports")) }
        finally { directory.deleteRecursively() }
    }
}
