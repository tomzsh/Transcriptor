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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun firstTranslationUsesObservedDocumentMetadataEvenWhenLibraryHasNotUpdated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val stale = ProjectEntity("preview", "Recording", "/nonexistent.wav", "audio/wav", 3000,
                "whisper-tiny", "en", false, ProjectState.COMPLETED, createdAt = 1)
            db.transcriptDao().insertProject(stale.copy(translationLanguage = "id"))
            db.transcriptDao().putSegments(listOf(SegmentEntity("s", "preview", 0, 0, 2000, "Hello", null, translatedText = "Halo")))
            val repository = MediaProjectRepository(context, db.transcriptDao())
            compose.setContent {
                TranscriptorTheme(ThemeMode.LIGHT) {
                    MediaProjectPreview(stale.toDomain(), repository, PaddingValues(0.dp), {}, {}, {})
                }
            }
            compose.waitUntilAtLeastOneExists(hasText("Terjemahan · Indonesia") and isEnabled(), 15_000)
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Terjemahan · Indonesia"))
            compose.onNodeWithText("Terjemahan · Indonesia").assertExists().performClick()
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Halo"))
            compose.onNodeWithText("Halo").assertExists()
            Unit
        } finally { db.close() }
    }
}
