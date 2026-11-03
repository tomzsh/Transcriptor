package id.tomz.transcriptor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.domain.TranscriptSpeaker
import id.tomz.transcriptor.domain.TranscriptSegment
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptEditorFailureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun storageFailureDuringRenameShowsFeedbackWithoutClosingEditor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { TranscriptorTheme(ThemeMode.LIGHT) {
            TranscriptSpeakerEditor(TranscriptSpeaker("s", "p", "Ana"), onRename = { _, _ ->
                throw IOException("Storage unavailable")
            })
        } }
        compose.onNodeWithText(context.getString(R.string.transcript_speaker_save)).performClick()
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
    }

    @Test fun storageFailureDuringSpeakerCreationKeepsEnteredName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { TranscriptorTheme(ThemeMode.LIGHT) {
            TranscriptSpeakerCreator("p", onCreate = { _, _ -> throw IOException("Storage unavailable") }, enabled = true)
        } }
        compose.onNodeWithText(context.getString(R.string.transcript_speaker_new_name)).performTextInput("Ana")
        compose.onNodeWithText(context.getString(R.string.transcript_speaker_add)).performClick()
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
        compose.onNodeWithText("Ana").assertIsDisplayed()
    }

    @Test fun storageFailureDuringCueEditKeepsTextAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { TranscriptorTheme(ThemeMode.LIGHT) {
            TranscriptCueEditor(TranscriptSegment("s", "p", 0, 0, 1_000, "Hello", null), emptyList(),
                onSave = { throw IOException("Storage unavailable") }, onSeek = {},
                showTimestamps = false, showSpeakers = false)
        } }
        compose.onNodeWithText(context.getString(R.string.transcript_edit_save)).performClick()
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
        compose.onNodeWithText("Hello").assertIsDisplayed()
    }
}
