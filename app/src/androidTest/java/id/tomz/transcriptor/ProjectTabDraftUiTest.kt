package id.tomz.transcriptor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.data.*
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.translation.AiTranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ProjectTabDraftUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unsavedCueTextSurvivesDetailsTabWithoutUpdatingStoredTranscript() = runBlocking {
        withPreviewProject { database, project ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val editLabel = context.getString(R.string.edit_segments)
            val textField = hasText(context.getString(R.string.transcript_text)) and hasSetTextAction()
            compose.waitUntilAtLeastOneExists(hasText(editLabel), 15_000)
            compose.onNodeWithText(editLabel).performScrollTo().performClick()
            compose.onNode(textField).performScrollTo().performTextReplacement("Unsaved interview correction.")
            compose.onNode(textField).assertTextContains("Unsaved interview correction.")

            compose.onNodeWithText(context.getString(R.string.transcript_details)).performClick()
            compose.onNodeWithText(context.getString(R.string.transcript_title)).performClick()

            compose.onNode(textField).performScrollTo().assertTextContains("Unsaved interview correction.")
            assertEquals("Original transcript.", database.transcriptDao().segments(project.id).single().text)
        }
    }

    @Test fun unsavedTranslationConfigurationSurvivesDetailsTabWithoutSavingSettings() = runBlocking {
        withPreviewProject { _, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val settings = AiTranslationSettings(context)
            val before = settings.read()
            val translationTab = hasText(context.getString(R.string.translation_result)) and isSelectable()
            val endpointField = hasText(context.getString(R.string.translation_endpoint)) and hasSetTextAction()
            val modelField = hasText(context.getString(R.string.translation_cloud_model)) and hasSetTextAction()
            val guidanceField = hasText(context.getString(R.string.translation_guidance)) and hasSetTextAction()
            compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.edit_segments)), 15_000)
            compose.onNode(translationTab).performClick()
            compose.onNodeWithText(context.getString(R.string.translation_mode_cloud)).performScrollTo().performClick()
            compose.onNode(endpointField).performScrollTo().performTextReplacement("https://example.com/v1")
            compose.onNode(modelField).performScrollTo().performTextReplacement("draft-model")
            compose.onNode(guidanceField).performScrollTo().performTextReplacement("Preserve all product names.")
            compose.onNode(guidanceField).assertTextContains("Preserve all product names.")
            compose.onNode(endpointField).performScrollTo().assertTextContains("https://example.com/v1")
            compose.onNode(modelField).performScrollTo().assertTextContains("draft-model")

            compose.onNodeWithText(context.getString(R.string.transcript_details)).performClick()
            compose.onNode(translationTab).performClick()

            compose.onNode(guidanceField).performScrollTo().assertTextContains("Preserve all product names.")
            compose.onNode(endpointField).performScrollTo().assertTextContains("https://example.com/v1")
            compose.onNode(modelField).performScrollTo().assertTextContains("draft-model")
            val after = settings.read()
            assertEquals(before.endpoint, after.endpoint)
            assertEquals(before.model, after.model)
            assertEquals(before.guidance, after.guidance)
        }
    }

    private suspend fun withPreviewProject(block: suspend (TranscriptDatabase, ProjectEntity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val project = ProjectEntity("tab-draft-${java.util.UUID.randomUUID()}", "Draft test", "/nonexistent.wav",
                "audio/wav", 3000, "whisper-tiny", "en", false, ProjectState.COMPLETED, createdAt = 1)
            database.transcriptDao().insertProject(project)
            database.transcriptDao().putSegments(listOf(
                SegmentEntity("cue-${project.id}", project.id, 0, 0, 2000, "Original transcript.", null),
            ))
            val repository = MediaProjectRepository(context, database.transcriptDao())
            compose.setContent {
                TranscriptorTheme(ThemeMode.LIGHT) {
                    MediaProjectPreview(project.toDomain(), repository, PaddingValues(0.dp), {}, {}, {})
                }
            }
            block(database, project)
        } finally {
            database.close()
        }
    }
}
