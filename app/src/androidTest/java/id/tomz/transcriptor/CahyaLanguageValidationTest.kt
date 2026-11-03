package id.tomz.transcriptor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.whisper.WhisperTranscriber
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CahyaLanguageValidationTest {
    @Test fun incompatibleLanguageFailsBeforeModelOrAudioAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingAudio = File(context.cacheDir, "cahya-language-${UUID.randomUUID()}.f32le")
        assertFalse(missingAudio.exists())

        try {
            WhisperTranscriber(context).transcribe(
                modelId = "whisper-cahya-medium-id-q5",
                normalizedAudio = missingAudio,
                language = "en",
                onProgress = {},
            )
            fail("Cahya must reject an explicit language other than Indonesian")
        } catch (error: IOException) {
            assertEquals("Model ini hanya mendukung transkripsi bahasa Indonesia", error.message)
        }
    }
}
