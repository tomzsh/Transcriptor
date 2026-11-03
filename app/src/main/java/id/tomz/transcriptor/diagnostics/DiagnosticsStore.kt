package id.tomz.transcriptor.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Each Android process has its own two rotating files and one independently writable crash slot. */
internal class DiagnosticsStore(
    private val directory: File,
    private val process: String,
    private val maxFileBytes: Int = 256 * 1_024,
    private val retentionMs: Long = RETENTION_MS,
) {
    init { require(process in setOf("main", "inference", "speaker", "other")); require(maxFileBytes > 32) }

    @Synchronized fun append(json: String, now: Long = System.currentTimeMillis()) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > minOf(maxFileBytes, MAX_RECORD_BYTES)) return
        check(directory.isDirectory || directory.mkdirs())
        val current = File(directory, "$process.current.jsonl")
        if (current.length() + bytes.size > maxFileBytes) {
            val previous = File(directory, "$process.previous.jsonl")
            if (previous.exists()) check(previous.delete())
            if (!current.renameTo(previous)) {
                current.copyTo(previous, overwrite = true)
                check(current.delete())
            }
        }
        FileOutputStream(current, true).use { it.write(bytes) }
        current.setLastModified(now)
    }

    // No writer monitor here: the crashing thread could have interrupted the normal writer itself.
    fun writeCrash(json: String) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_RECORD_BYTES) return
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(File(directory, "$process.crash.jsonl"), false).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    fun logFiles(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && LOG_NAME.matches(it.name) && it.canonicalFile.parentFile == directory.canonicalFile }
        .sortedBy { it.name }

    fun prune(now: Long = System.currentTimeMillis()) {
        logFiles().filter { now - it.lastModified() > retentionMs }.forEach { it.delete() }
    }

    fun historyCursor(): Long = File(directory, HISTORY_CURSOR).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L

    fun updateHistoryCursor(timestamp: Long) {
        check(directory.isDirectory || directory.mkdirs())
        File(directory, HISTORY_CURSOR).writeText(timestamp.toString())
    }

    fun historyCheckedRecently(now: Long): Boolean {
        val modified = File(directory, HISTORY_CURSOR).lastModified()
        return modified > 0 && now >= modified && now - modified < 60_000
    }

    @Synchronized fun export(exports: File, metadata: String): File {
        val now = System.currentTimeMillis()
        prune(now)
        check(exports.isDirectory || exports.mkdirs())
        pruneExports(exports, now)
        // A fixed name avoids exposing a project title, source path, or user identifier.
        val output = File.createTempFile("transcriptor-diagnostics-", ".zip", exports)
        try {
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(EXPORT_README.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (file in logFiles()) {
                    // Another process can append while exporting; copy a bounded snapshot of complete records.
                    val bytes = file.inputStream().use { input ->
                        val buffer = ByteArray(minOf(file.length(), MAX_EXPORT_FILE_BYTES.toLong()).toInt())
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                    val newline = bytes.indexOfLast { it == '\n'.code.toByte() }
                    if (newline < 0) continue
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    zip.write(bytes, 0, newline + 1)
                    zip.closeEntry()
                }
            }
            pruneExports(exports, now, keep = output)
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    @Synchronized fun clear(exports: File, now: Long = System.currentTimeMillis()) {
        logFiles().forEach { check(it.delete() || !it.exists()) }
        exports.listFiles().orEmpty().filter { EXPORT_NAME.matches(it.name) }.forEach { check(it.delete() || !it.exists()) }
        // Clearing must not reimport the same OS crash history on the next launch.
        updateHistoryCursor(now)
    }

    private fun pruneExports(exports: File, now: Long, keep: File? = null) {
        val files = exports.listFiles().orEmpty().filter { it.isFile && EXPORT_NAME.matches(it.name) }
            .sortedByDescending { it.lastModified() }
        var retained = if (keep == null) 0 else 1
        files.filter { it != keep }.forEach { file ->
            if (now - file.lastModified() > EXPORT_RETENTION_MS || retained >= 2) file.delete()
            else retained++
        }
    }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        private const val EXPORT_RETENTION_MS = 24L * 60 * 60 * 1_000
        private const val MAX_RECORD_BYTES = 8 * 1_024
        private const val MAX_EXPORT_FILE_BYTES = 256 * 1_024
        private const val HISTORY_CURSOR = "exit-history-cursor"
        private val LOG_NAME = Regex("(main|inference|speaker|other)\\.(current|previous|crash)\\.jsonl")
        private val EXPORT_NAME = Regex("transcriptor-diagnostics-[A-Za-z0-9_-]+\\.zip")
        private const val EXPORT_README = """Transcriptor local development diagnostics
Schema: 1. Timestamps are UTC Unix milliseconds; durations are milliseconds; memory fields include their units.
Logs rotate at 256 KiB per file, two files plus one bounded crash slot per process. Files inactive for 7 days are removed on cleanup; active bounded files may contain older events.
This archive is created only when you choose Export. The app never uploads diagnostics automatically.
Contains app/device/ABI information, operational events, memory/thermal samples, Java exception class/code frames,
and Android process-exit metadata where available (Android 11+). It contains no transcript, source media,
API key, exception message, source path, raw native tombstone, or ANR trace. Device model and event times
may still identify your testing session; review this archive before choosing where to share it.
Native stacks and native crash details are intentionally excluded. Android exit history availability varies by device.
"""
    }
}
