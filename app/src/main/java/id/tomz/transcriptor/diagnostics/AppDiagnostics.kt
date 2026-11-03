package id.tomz.transcriptor.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class DiagnosticsSummary(val fileCount: Int, val totalBytes: Long)

/** Local only, bounded development diagnostics. Never pass user content as an event code. */
object AppDiagnostics {
    @Volatile private var state: State? = null
    private val crashing = AtomicBoolean(false)
    private val writer = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { command ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                command.run()
            }, "local-diagnostics").apply { isDaemon = true }
        },
    )

    /** Installs one handler per Android process; disk reads/history collection happen on the writer. */
    @Synchronized fun initialize(context: Context) {
        if (state != null) return
        val app = context.applicationContext ?: context
        val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
            runCatching {
                app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                    ?.firstOrNull { it.pid == Process.myPid() }?.processName
            }.getOrNull()
        }
        val role = DiagnosticsPrivacy.process(processName, app.packageName)
        val initialized = State(app, DiagnosticsStore(File(app.noBackupFilesDir, "diagnostics"), role), role)
        state = initialized
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticsCrashHandler(previous,
            persist = { error ->
                if (crashing.compareAndSet(false, true)) {
                    initialized.store.writeCrash(DiagnosticsJson.encode(record(
                        initialized, "uncaught_exception", DiagnosticsPrivacy.failure(error),
                    )))
                }
            },
            terminate = { Process.killProcess(Process.myPid()); exitProcess(10) },
        ))
        enqueue {
            initialized.store.prune()
            initialized.version = appVersion(app)
            initialized.store.append(DiagnosticsJson.encode(record(initialized, "app_start", deviceMetadata(initialized))))
            sampleResources(initialized, force = true)
            collectExitHistory(initialized)
        }
    }

    /** [code] is a static developer constant. Fields reject arbitrary text, paths and unknown keys. */
    fun event(code: String, fields: Map<String, Any?> = emptyMap()) {
        val initialized = state ?: return
        val safeCode = DiagnosticsPrivacy.code(code)
        val safeFields = DiagnosticsPrivacy.fields(fields)
        enqueue {
            initialized.store.append(DiagnosticsJson.encode(record(initialized, safeCode, safeFields)))
            sampleResources(initialized)
        }
    }

    /** Exception messages, source file names, source paths and suppressed exceptions are excluded. */
    fun recordFailure(code: String, error: Throwable) {
        val initialized = state ?: return
        val safeCode = DiagnosticsPrivacy.code(code)
        // Capture a bounded immutable snapshot now: callers may modify a Throwable after returning.
        val safeFailure = runCatching { DiagnosticsPrivacy.failure(error) }.getOrDefault(mapOf("exception_class" to "unknown"))
        enqueue {
            initialized.store.append(DiagnosticsJson.encode(record(initialized, safeCode, safeFailure)))
            sampleResources(initialized)
        }
    }

    /** Creates a ZIP only in response to the user's export action. This method never sends it. */
    suspend fun export(context: Context): File {
        initialize(context)
        return serialized {
            val initialized = checkNotNull(state)
            sampleResources(initialized, force = true)
            collectExitHistory(initialized, force = true)
            initialized.store.export(exportDirectory(context), DiagnosticsJson.encode(deviceMetadata(initialized) + mapOf(
                "schema" to 1, "exported_at_ms" to System.currentTimeMillis(), "inactive_file_retention_days" to 7,
            )))
        }
    }

    /** Clears all processes' retained logs and archives. Later activity may create new events. */
    suspend fun clear(context: Context) {
        initialize(context)
        serialized {
            checkNotNull(state).store.clear(exportDirectory(context))
        }
    }

    suspend fun snapshot(context: Context): DiagnosticsSummary {
        initialize(context)
        return serialized {
            val store = checkNotNull(state).store
            store.prune()
            val files = store.logFiles()
            DiagnosticsSummary(files.size, files.sumOf { it.length() })
        }
    }

    private fun enqueue(action: () -> Unit) {
        try {
            writer.execute { runCatching(action) }
        } catch (_: RejectedExecutionException) {
            // A bounded queue prevents rapid native progress events from allocating without limit.
            // Diagnostics must never stall inference or the UI just to preserve a log event.
        }
    }

    private suspend fun <T> serialized(action: () -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val command = Runnable {
                try { continuation.resume(action()) }
                catch (error: Throwable) { continuation.resumeWithException(error) }
            }
            try { writer.execute(command) }
            catch (_: RejectedExecutionException) {
                // User initiated export/clear must not be dropped when the event queue is full.
                try { writer.queue.put(command) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    private fun record(state: State, code: String, fields: Map<String, Any>): Map<String, Any> = mapOf(
        "schema" to 1, "timestamp_ms" to System.currentTimeMillis(),
        "uptime_ms" to SystemClock.elapsedRealtime(), "process" to state.role,
        "app_version" to state.version, "event" to code, "fields" to fields,
    )

    private fun sampleResources(state: State, force: Boolean = false) {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && elapsed - state.lastSampleElapsedMs < 30_000) return
        state.lastSampleElapsedMs = elapsed
        val runtime = Runtime.getRuntime()
        val fields = mutableMapOf<String, Any>(
            "java_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
            "java_max_bytes" to runtime.maxMemory(), "native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "cpu_count" to runtime.availableProcessors(),
        )
        runCatching {
            val memory = ActivityManager.MemoryInfo()
            state.context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            fields["available_bytes"] = memory.availMem
            fields["total_bytes"] = memory.totalMem
            fields["low_memory"] = memory.lowMemory
        }
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            state.context.getSystemService(PowerManager::class.java)?.let { fields["thermal_status"] = it.currentThermalStatus }
        }
        state.store.append(DiagnosticsJson.encode(record(state, "resource_snapshot", fields)))
    }

    private fun deviceMetadata(state: State): Map<String, Any> = mapOf(
        "app_version" to state.version, "android_api" to Build.VERSION.SDK_INT,
        "android_release" to Build.VERSION.RELEASE.take(32),
        "manufacturer" to Build.MANUFACTURER.take(80), "device_model" to Build.MODEL.take(80),
        "abis" to Build.SUPPORTED_ABIS.take(8).map { it.take(32) },
        "cpu_count" to Runtime.getRuntime().availableProcessors(), "process" to state.role,
    )

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName.orEmpty().take(32)} ($code)"
    }.getOrDefault("unknown")

    private fun collectExitHistory(state: State, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30 || state.role != "main") return
        val now = System.currentTimeMillis()
        if (!force && state.store.historyCheckedRecently(now)) return
        val cursor = state.store.historyCursor()
        val manager = state.context.getSystemService(ActivityManager::class.java) ?: return
        val records = manager.getHistoricalProcessExitReasons(state.context.packageName, 0, 24)
        records.filter { it.timestamp > cursor && now - it.timestamp <= DiagnosticsStore.RETENTION_MS }
            .sortedBy { it.timestamp }.forEach { exit ->
                // Never read description, processStateSummary, traceInputStream, or toString().
                val fields = DiagnosticsPrivacy.exitFields(
                    exit.processName, state.context.packageName, exit.reason, exit.status, exit.pss, exit.rss, exit.timestamp,
                )
                state.store.append(DiagnosticsJson.encode(record(state, "process_exit", fields)))
            }
        state.store.updateHistoryCursor(maxOf(cursor, records.maxOfOrNull { it.timestamp } ?: 0L))
    }

    private fun exportDirectory(context: Context): File = File(context.cacheDir, "diagnostics-exports")

    private class State(val context: Context, val store: DiagnosticsStore, val role: String) {
        @Volatile var version: String = "unknown"
        var lastSampleElapsedMs = 0L
    }
}
