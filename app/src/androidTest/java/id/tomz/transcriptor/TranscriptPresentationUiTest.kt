package id.tomz.transcriptor

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptPresentationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readerDefaultsToCleanParagraphAndMetadataCanBeRevealed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectId = "presentation-test-${java.util.UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("transcript_presentation", 0)
        val doc = TranscriptDocument(
            TranscriptProject(projectId, "Test", "", "audio/wav", 3000, "tiny", "en", false, ProjectState.COMPLETED),
            listOf(TranscriptSegment("a", projectId, 0, 100, 1000, "Hello.", "speaker"),
                TranscriptSegment("b", projectId, 1, 1000, 3000, "Good morning.", "speaker")),
            listOf(TranscriptSpeaker("speaker", projectId, "Ana")),
        )
        try {
        compose.setContent {
            TranscriptorTheme(ThemeMode.LIGHT) {
                val state = rememberPresentation(doc.project.id)
                Column { TranscriptAppearance(state); TranscriptParagraph(doc, state.options, "") }
            }
        }
        compose.onNodeWithText("Hello. Good morning.").assertExists()
        compose.onNodeWithText("Ana:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("00:00:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tampilan").performClick()
        compose.onNodeWithText("Tampilkan waktu").performClick()
        compose.onNodeWithText("Tampilkan pembicara").performClick()
        compose.onNodeWithText("Selesai").performClick()
        compose.onNodeWithText("Ana:", substring = true).assertExists()
        compose.onNodeWithText("00:00:00.100", substring = true).assertExists()
        } finally {
            preferences.edit().remove("$projectId.paragraphs").remove("$projectId.times")
                .remove("$projectId.speakers").commit()
        }
    }
}
