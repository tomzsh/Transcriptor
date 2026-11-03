package id.tomz.transcriptor

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AppLanguageSwitchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var original: AppLanguage? = null

    @After fun restoreLanguage() {
        original?.let { language -> compose.runOnUiThread { AppLanguageSettings.select(compose.activity, language) } }
    }

    @Test fun changingInterfaceLanguageKeepsModelNavigationAndRemembersChoice() {
        original = AppLanguageSettings.read(compose.activity)
        compose.runOnUiThread { AppLanguageSettings.select(compose.activity, AppLanguage.INDONESIAN) }
        compose.waitUntilAtLeastOneExists(hasText("Atur"), 15_000)
        compose.onNodeWithText("Model").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Model transkripsi"), 15_000)
        compose.onNode(hasText("Pengaturan") and hasClickAction()).performClick()
        compose.onNodeWithText("Bahasa aplikasi").performClick()
        compose.onNodeWithText("English").performClick()
        compose.waitUntilAtLeastOneExists(hasText("App language"), 15_000)
        assertEquals(AppLanguage.ENGLISH, AppLanguageSettings.read(compose.activity))
        compose.onNode(hasText("Models") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Speech models"), 15_000)

        // A second recreation must use the saved language and retain the current destination.
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitUntilAtLeastOneExists(hasText("Speech models"), 15_000)
        compose.onNode(hasText("Settings") and hasClickAction()).performClick()
        compose.onNodeWithText("App language").performClick()
        compose.onNodeWithText("Bahasa Indonesia").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Bahasa aplikasi"), 15_000)
        assertEquals(AppLanguage.INDONESIAN, AppLanguageSettings.read(compose.activity))
        compose.onNode(hasText("Model") and hasClickAction()).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Model transkripsi"), 15_000)
    }

    @Test fun bothCatalogsLocalizeWorkflowsAndKeepCreatorIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tag))
        })
        val english = localized("en")
        val indonesian = localized("id,en")
        assertEquals("Transcript", english.getString(R.string.transcript_title))
        assertEquals("Transkrip", indonesian.getString(R.string.transcript_title))
        assertEquals("Translate transcript", english.getString(R.string.translation_start))
        assertEquals("Terjemahkan transkrip", indonesian.getString(R.string.translation_start))
        assertEquals("Share diagnostics report", english.getString(R.string.settings_share_diagnostics))
        assertEquals("Bagikan laporan diagnostik", indonesian.getString(R.string.settings_share_diagnostics))
        assertEquals("GitHub · tomzsh", english.getString(R.string.settings_github))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromTag("in-ID"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
    }
}
