package id.tomz.transcriptor.domain

import org.junit.Assert.*
import org.junit.Test

class TranscriptionLanguagesTest {
    @Test fun translationRequiresSupportedAndDistinctLanguages() {
        TranslationLanguages.validate("en", "id")
        assertTrue(runCatching { TranslationLanguages.validate("auto", "id") }.isFailure)
        assertTrue(runCatching { TranslationLanguages.validate("en", "en") }.isFailure)
        TranslationLanguages.validate("jw", "en")
        assertTrue(runCatching { TranslationLanguages.validate("invalid", "en") }.isFailure)
    }
    @Test fun searchesLocalizedNativeAndCodeWithoutCaseSensitivity() {
        assertEquals(listOf("ja"), TranscriptionLanguages.search("jepang").map { it.code })
        assertEquals(listOf("ja"), TranscriptionLanguages.search("日本語").map { it.code })
        assertTrue(TranscriptionLanguages.search(" ID ").any { it.code == "id" })
        assertTrue(TranscriptionLanguages.search("not-a-language").isEmpty())
    }

    @Test fun englishOnlyNormalizesEveryOtherLanguage() {
        listOf("id", "ja", "auto", "bad").forEach {
            assertEquals("en", TranscriptionLanguages.forModel(it, englishOnly = true))
        }
        assertEquals("ja", TranscriptionLanguages.forModel("ja", englishOnly = false))
        assertEquals("auto", TranscriptionLanguages.forModel("bad", englishOnly = false))
    }

    @Test fun dedicatedIndonesianModelNormalizesAutoAndIncompatibleSelections() {
        listOf("auto", "id", "en", "ar", "bad").forEach {
            assertEquals("id", TranscriptionLanguages.forModel(it, englishOnly = false, transcriptionLanguage = "id"))
        }
    }

    @Test fun generalModelsKeepAllSupportedLanguageSelections() {
        listOf("auto", "id", "en", "ar", "jw").forEach {
            assertEquals(it, TranscriptionLanguages.forModel(it, englishOnly = false, transcriptionLanguage = null))
        }
        assertEquals("en", TranscriptionLanguages.forModel("auto", englishOnly = true, transcriptionLanguage = null))
    }

    @Test fun excludesLanguagesUnsupportedByTinyBaseSmall() {
        assertFalse(TranscriptionLanguages.supports("yue"))
        assertTrue(TranscriptionLanguages.supports("jw"))
        assertTrue(TranscriptionLanguages.supports("su"))
    }

    @Test fun translatedDocumentKeepsOriginalCuesAndRequiresCompleteTranslation() {
        val original = TranscriptDocument(
            TranscriptProject("p", "Test", "test.wav", "audio/wav", 3000, "whisper-tiny", "en", false,
                ProjectState.COMPLETED, translationLanguage = "id"),
            listOf(TranscriptSegment("s", "p", 0, 100, 2000, "Hello", null, translatedText = "Halo")),
            emptyList(),
        )
        val translated = original.translatedDocument()!!
        assertEquals("Hello", original.segments.single().text)
        assertEquals("Halo", translated.segments.single().text)
        assertEquals(100L, translated.segments.single().startMs)
        assertEquals(2000L, translated.segments.single().endMs)
        assertEquals("id", translated.project.language)
        assertNull(original.copy(segments = original.segments.map { it.copy(translatedText = null) }).translatedDocument())
    }
}
