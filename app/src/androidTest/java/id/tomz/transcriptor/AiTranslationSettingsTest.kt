package id.tomz.transcriptor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.translation.AiTranslationConfig
import id.tomz.transcriptor.translation.AiTranslationMode
import id.tomz.transcriptor.translation.AiTranslationSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiTranslationSettingsTest {
    @Test fun apiKeyRoundTripsThroughKeystoreWithoutEnteringPreferencesOrBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AiTranslationSettings(context)
        val previous = settings.read()
        val secret = "test-transcriptor-secret-do-not-send"
        try {
            settings.save(AiTranslationConfig(mode = AiTranslationMode.OFFLINE, apiKey = secret, guidance = "Preserve names"))
            assertEquals(secret, AiTranslationSettings(context).read().apiKey)
            assertEquals(AiTranslationMode.OFFLINE, settings.read().mode)
            val encrypted = File(context.noBackupFilesDir, "ai-translation-key.enc")
            assertTrue(encrypted.isFile)
            assertFalse(encrypted.readText().contains(secret))
            assertFalse(context.getSharedPreferences("ai_translation", 0).all.values.any { it.toString().contains(secret) })
            assertFalse(settings.read().toString().contains(secret))
        } finally { settings.save(previous) }
    }
}
