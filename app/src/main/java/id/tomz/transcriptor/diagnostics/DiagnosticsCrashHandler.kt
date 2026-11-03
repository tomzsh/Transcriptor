package id.tomz.transcriptor.diagnostics

/** A synchronous, best effort crash slot that always hands termination back to Android. */
internal class DiagnosticsCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val persist: (Throwable) -> Unit,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { runCatching { persist(error) } }
        finally {
            if (previous != null) previous.uncaughtException(thread, error) else terminate()
        }
    }
}
