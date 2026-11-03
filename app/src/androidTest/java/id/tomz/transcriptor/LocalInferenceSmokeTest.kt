package id.tomz.transcriptor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.translation.*
import id.tomz.transcriptor.whisper.WhisperTranscriber
import id.tomz.transcriptor.model.localModelDirectory
import id.tomz.transcriptor.inference.NativeInferenceWorker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeout

/** Opt-in real-model checks; the regular suite has no network or large-fixture requirement. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceSmokeTest {
    @Test fun importedModelMetadataDoesNotStallStartup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.noBackupFilesDir, "offline-ai/model.gguf")
        assertTrue("Install the Qwen GGUF fixture first", fixture.isFile)
        val start = android.os.SystemClock.elapsedRealtime()
        assertFalse(GgufTranslationModel.isTranslateGemma(fixture))
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        assertTrue("Metadata inspection took $elapsed ms", elapsed < 15_000)
        id.tomz.transcriptor.diagnostics.AppDiagnostics.event("test.metadata_inspected", mapOf("duration_ms" to elapsed))
    }

    @Test fun quantizedMobileWhisperModelsLoadAndRecognizePublicFixture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileWhisperSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = NativeInferenceWorker(context)
        // Turbo can be selected explicitly on a test device with sufficient working memory.
        val models = InstrumentationRegistry.getArguments().getString("mobileWhisperModels")
            ?.split(',') ?: listOf("whisper-small-q5")
        for (model in models) {
            val progress = mutableListOf<Int>()
            val output = worker.transcribe(model, File(context.cacheDir, "smoke.f32le"), "en") { progress.add(it) }
            assertTrue("$model must recognize speech", output.any { it.text.contains("country", ignoreCase = true) })
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun repeatedWhisperSessionsReleaseNativeResourcesBeforeTheNextJob() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whisperSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = NativeInferenceWorker(context)
        repeat(2) {
            val output = worker.transcribe("whisper-tiny", File(context.cacheDir, "smoke.f32le"), "en") {}
            assertTrue("Transcription ${it + 1} must work", output.any { segment -> segment.text.contains("country", ignoreCase = true) })
        }
    }

    @Test fun whisperSurvivesWorkerDeathAndRetriesOnCpu() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whisperSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = NativeInferenceWorker(context)
        val killed = AtomicBoolean(false)
        val output = withTimeout(180_000) {
            worker.transcribe("whisper-tiny", File(context.cacheDir, "smoke.f32le"), "en") { progress ->
                if (progress in 0..99 && killed.compareAndSet(false, true)) worker.cancel()
            }
        }
        assertTrue("The test must interrupt the native worker", killed.get())
        assertTrue(output.any { it.text.contains("country", ignoreCase = true) })
    }

    @Test fun realWhisperRecognizesFixtureAndReportsMonotonicProgress() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("whisperSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(localModelDirectory(context), "ggml-tiny.bin")
        val fixture = File(context.cacheDir, "smoke.f32le")
        assertTrue("Install Tiny and push normalized audio fixture first", model.isFile && fixture.isFile)
        val progress = mutableListOf<Int>()
        val output = NativeInferenceWorker(context).transcribe("whisper-tiny", fixture, "en") { progress.add(it) }
        assertTrue(output.any { it.text.contains("country", ignoreCase = true) })
        assertTrue(output.all { it.startMs >= 0 && it.endMs > it.startMs })
        assertTrue(progress.isNotEmpty())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun realTranslationTranslatesExistingTextWithSegmentProgress() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AiTranslationSettings(context)
        val previous = settings.read()
        settings.save(previous.copy(mode = AiTranslationMode.OFFLINE))
        try {
        val original = listOf("Hello, how are you?", "This is a test of text translation.")
        val progress = mutableListOf<Int>()
        var modelReady = false
        val output = TextTranslator(settings, CloudAiClient(), NativeInferenceWorker(context), OfflineModelStore(context)).translate(original, "en", "id", allowCellular = false,
            onModelReady = { modelReady = true }, onProgress = { progress.add(it) })
        assertTrue(modelReady)
        assertEquals(2, output.size)
        assertTrue(output.all { it.isNotBlank() })
        assertNotEquals(original, output)
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        assertEquals("Hello, how are you?", original.first())
        } finally { settings.save(previous) }
    }
    @Test fun realOfflineModelPreservesCueIdsWithNeighboringContext() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("translationSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = requireNotNull(OfflineModelStore(context).verifiedModelFile())
        val source = listOf("We rested by the bank.", "The river was calm and the water was clear.")
        val batch = ContextTranslation.nextBatch(source, 0, emptyList(), "en", "id", "", true)
        val raw = NativeInferenceWorker(context).withSession { session ->
            val first = session.generate(file.absolutePath, batch.system, batch.user,
                grammar = ContextTranslation.responseGrammar(batch.cueIds)) {}
            ContextTranslation.decodeResponse(first, batch.cueIds)
            // A second generation exercises reuse of the mapped model in the same worker session.
            session.generate(file.absolutePath, batch.system, batch.user,
                grammar = ContextTranslation.responseGrammar(batch.cueIds)) {}.also {
                assertEquals("Cleared KV/recurrent state must not change deterministic repeated output", first, it)
            }
        }
        // Synthetic public fixture only; never record a user's transcript in diagnostics.
        File(context.cacheDir, "ai-smoke-context.json").writeText(raw)
        val output = ContextTranslation.decodeResponse(raw, batch.cueIds)
        assertEquals(2, output.size)
        assertTrue(output.all { it.isNotBlank() })
        assertNotEquals(source, output)
    }

}
