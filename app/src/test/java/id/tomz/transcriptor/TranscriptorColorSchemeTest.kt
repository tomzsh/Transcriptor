package id.tomz.transcriptor

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptorColorSchemeTest {
    @Test fun darkThemeUsesDarkSurfacesAndLightText() {
        val scheme = transcriptorColorScheme(darkTheme = true)

        assertTrue(scheme.background.luminance() < 0.5f)
        assertTrue(scheme.onBackground.luminance() > 0.5f)
    }

    @Test fun lightThemeUsesLightSurfacesAndDarkText() {
        val scheme = transcriptorColorScheme(darkTheme = false)

        assertTrue(scheme.background.luminance() > 0.5f)
        assertTrue(scheme.onBackground.luminance() < 0.5f)
    }
}
