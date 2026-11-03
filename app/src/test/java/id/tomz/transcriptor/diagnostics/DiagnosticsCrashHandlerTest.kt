package id.tomz.transcriptor.diagnostics

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsCrashHandlerTest {
    @Test fun recordsSanitizedJavaFailureBeforeDelegatingOriginalError() {
        val root = Files.createTempDirectory("diagnostics-crash").toFile()
        try {
            val store = DiagnosticsStore(root, "inference")
            val error = IllegalStateException("secret words sk-private /private/audio.wav")
            var forwarded: Throwable? = null
            val handler = DiagnosticsCrashHandler(
                previous = Thread.UncaughtExceptionHandler { _, failure ->
                    assertTrue(store.logFiles().single().readText().contains("java.lang.IllegalStateException"))
                    forwarded = failure
                },
                persist = { failure -> store.writeCrash(DiagnosticsJson.encode(DiagnosticsPrivacy.failure(failure))) },
                terminate = { fail("Previous crash handler must be preserved") },
            )
            handler.uncaughtException(Thread.currentThread(), error)
            assertSame(error, forwarded)
            assertFalse(store.logFiles().single().readText().contains("secret words"))
        } finally { root.deleteRecursively() }
    }

    @Test fun diskFailureStillDelegatesAndDoesNotReplaceTheOriginalCrash() {
        val error = OutOfMemoryError("sensitive allocation details")
        var forwarded: Throwable? = null
        val handler = DiagnosticsCrashHandler(
            previous = Thread.UncaughtExceptionHandler { _, failure -> forwarded = failure },
            persist = { throw IOException("Disk full") },
            terminate = { fail("Previous crash handler must be preserved") },
        )
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, forwarded)
    }

    @Test fun absentPreviousHandlerStillTerminatesAfterBestEffortWrite() {
        var terminated = false
        val handler = DiagnosticsCrashHandler(null, persist = { throw IOException() }, terminate = { terminated = true })
        handler.uncaughtException(Thread.currentThread(), Error("private"))
        assertTrue(terminated)
    }
}
