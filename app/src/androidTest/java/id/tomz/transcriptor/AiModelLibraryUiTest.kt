package id.tomz.transcriptor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.tomz.transcriptor.model.ModelCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiModelLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun qwenGemmaAndLlamaAreDiscoverableInTheCentralModelSettings() {
        val modelButton = hasText("Model") and hasClickAction()
        compose.waitUntilAtLeastOneExists(modelButton, 15_000)
        compose.onNode(modelButton).performClick()
        compose.onNodeWithText("Penerjemah AI").performClick()
        ModelCatalog.translationModels.forEach { model ->
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(model.label))
            compose.onNodeWithText(model.label).assertIsDisplayed()
        }
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Impor file GGUF"))
        compose.onNodeWithText("Impor file GGUF").assertIsDisplayed()
    }
}
