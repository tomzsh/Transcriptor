package id.tomz.transcriptor

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectLibraryAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun automaticSpeakerToggleHasAccessibleNameAndTogglesFromItsControl() {
        composeRule.waitUntilAtLeastOneExists(hasText("Atur"), 15_000)
        composeRule.onNodeWithText("Atur").performClick()
        val speakerLabel = hasText("Kelompokkan pembicara otomatis")
        composeRule.waitUntilAtLeastOneExists(speakerLabel, 15_000)
        val option = isToggleable() and speakerLabel

        composeRule.onNode(option).performScrollTo().assertIsOff()
        composeRule.onNode(option).performClick()
        composeRule.onNode(option).assertIsOn()
        composeRule.onNode(option).performClick()
        composeRule.onNode(option).assertIsOff()
    }
}
