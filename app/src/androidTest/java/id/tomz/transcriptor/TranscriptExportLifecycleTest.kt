package id.tomz.transcriptor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.domain.*
import id.tomz.transcriptor.export.*
import androidx.compose.material3.Button
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptExportLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pickerResultIsSavedEvenWhenExportControlsAreNotComposed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("export-lifecycle-", ".srt", context.cacheDir)
        val shown = mutableStateOf(true)
        val request = AtomicInteger(-1)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                request.set(requestCode)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val original = TranscriptDocument(
            TranscriptProject("export", "Example", "source.wav", "audio/wav", 3000, "whisper-tiny", "en", false,
                ProjectState.COMPLETED, translationLanguage = "id"),
            listOf(TranscriptSegment("cue", "export", 0, 1000, 2000, "Hello", null, translatedText = "Halo")), emptyList(),
        )
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TranscriptorTheme(ThemeMode.LIGHT) {
                        val state = rememberTranscriptExportState()
                        if (shown.value) TranscriptExportControl(original.translatedDocument()!!, state = state)
                        else Text("Export controls are offscreen")
                    }
                }
            }
            compose.onNodeWithText("Ekspor transkrip").performClick()
            compose.onNodeWithText("SRT · subtitle").performClick()
            compose.waitUntil(10_000) { request.get() >= 0 }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle { registry.dispatchResult(request.get(), Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))) }
            compose.waitUntil(10_000) { output.length() > 0 }
            assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHalo\n", output.readText())
            assertEquals("Hello", original.segments.single().text)
        } finally { output.delete() }
    }
    @Test fun paragraphExportCapturesHiddenMetadataOptionsBeforePickerReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("export-paragraph-", ".txt", context.cacheDir)
        val request = AtomicInteger(-1)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                request.set(requestCode)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val document = TranscriptDocument(
            TranscriptProject("paragraph-export", "Notes", "", "audio/wav", 3000, "tiny", "en", false, ProjectState.COMPLETED),
            listOf(TranscriptSegment("a", "paragraph-export", 0, 0, 1000, "Hello.", "s"),
                TranscriptSegment("b", "paragraph-export", 1, 1000, 3000, "Good morning.", "s")),
            listOf(TranscriptSpeaker("s", "paragraph-export", "Ana")),
        )
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    TranscriptorTheme(ThemeMode.LIGHT) {
                        val state = rememberTranscriptExportState()
                        Button(onClick = { state.exportWithOptions(document, ExportFormat.TXT, ExportOptions(false, false, true)) }) { Text("Export paragraph") }
                    }
                }
            }
            compose.onNodeWithText("Export paragraph").performClick()
            compose.waitUntil(10_000) { request.get() >= 0 }
            compose.runOnIdle { registry.dispatchResult(request.get(), Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))) }
            compose.waitUntil(10_000) { output.length() > 0 }
            assertEquals("Notes\n\nHello. Good morning.\n", output.readText())
        } finally { output.delete() }
    }

}
