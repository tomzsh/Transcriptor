package id.tomz.transcriptor

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.diagnostics.AppDiagnostics
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsExportTest {
    @Test fun exportIsReadableThroughProviderAndExcludesSensitiveFieldsAndMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDiagnostics.event("test.privacy_boundary", mapOf("threads" to 2, "transcript" to "PRIVATE_TRANSCRIPT_TEST", "api_key" to "PRIVATE_KEY_TEST"))
        AppDiagnostics.recordFailure("test.failure", IllegalStateException("PRIVATE_ERROR_TEST /private/audio.wav"))
        val archive = AppDiagnostics.export(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", archive)
        context.contentResolver.openInputStream(uri).use { assertNotNull(it); assertTrue(it!!.read() >= 0) }
        ZipFile(archive).use { zip ->
            assertNotNull(zip.getEntry("metadata.json"))
            val text = zip.entries().asSequence().filter { it.name.endsWith(".jsonl") }
                .joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
            assertTrue(text.contains("test.privacy_boundary"))
            assertTrue(text.contains("java.lang.IllegalStateException"))
            assertFalse(text.contains("PRIVATE_TRANSCRIPT_TEST"))
            assertFalse(text.contains("PRIVATE_KEY_TEST"))
            assertFalse(text.contains("PRIVATE_ERROR_TEST"))
            assertFalse(text.contains("/private/audio.wav"))
        }
        AppDiagnostics.clear(context)
        assertFalse(archive.exists())
        assertEquals(0, AppDiagnostics.snapshot(context).fileCount)
    }
}
