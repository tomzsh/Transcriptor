package id.tomz.transcriptor.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateGemmaPromptTest {
    @Test fun oneFullCueIsTheOnlyTextToTranslateAndReferencesStaySeparate() {
        val source = "cue one\nwith a line break"
        val batch = TranslateGemmaPrompt.nextBatch(
            texts = listOf("old", "previous", source, "next", "later"),
            start = 2, previousTranslations = listOf("lama", "sebelumnya"),
            source = "en", target = "id", guidance = "Use natural spoken Indonesian",
        )

        assertEquals(listOf(2), batch.cueIds)
        assertEquals(source, batch.user)
        val fields = batch.system.removePrefix(TranslateGemmaPrompt.SYSTEM_PREFIX).split('\t')
        assertEquals(5, fields.size)
        assertEquals(listOf("en", "id", "English", "Indonesian"), fields.take(4))
        val reference = fields.last()
        assertTrue(reference.contains("previous"))
        assertTrue(reference.contains("sebelumnya"))
        assertTrue(reference.contains("next"))
        assertTrue(reference.contains("Use natural spoken Indonesian"))
        assertFalse(reference.contains(source))
        assertFalse(batch.user.contains("⟦CUE:"))
    }

    @Test fun plainTranslationRestoresExactlyOneCueWithoutOutputMarkers() {
        assertEquals(listOf("Hai.\nApa kabar?"),
            TranslateGemmaPrompt.decodeResponse("  Hai.\nApa kabar?  ", listOf(7)))
    }

    @Test fun decoderNeverGuessesMultipleCueBoundaries() {
        assertRejected { TranslateGemmaPrompt.decodeResponse("Satu. Dua.", listOf(1, 2)) }
        assertRejected { TranslateGemmaPrompt.decodeResponse("Satu", emptyList()) }
        assertRejected { TranslateGemmaPrompt.decodeResponse("Satu", listOf(-1)) }
    }

    @Test fun markerLookingDialogueIsPreservedAsOrdinaryText() {
        val text = "The label is ⟦CUE:7⟧ and <title> is a tag."
        val batch = TranslateGemmaPrompt.nextBatch(listOf(text), 0, emptyList(), "en", "id", "")
        assertEquals(text, batch.user)
        val output = "Labelnya ⟦CUE:7⟧ dan <title> adalah tag."
        assertEquals(listOf(output), TranslateGemmaPrompt.decodeResponse(output, listOf(0)))
    }

    @Test fun nativeReferenceFieldEscapesTabsNewlinesAndBackslashes() {
        val batch = TranslateGemmaPrompt.nextBatch(
            listOf("Hello", "next\tline\nC:\\folder"), 0, emptyList(), "en", "id",
            "Use\tformal\r\nstyle\\tone",
        )
        val reference = batch.system.removePrefix(TranslateGemmaPrompt.SYSTEM_PREFIX).split('\t').also {
            assertEquals(5, it.size)
        }.last()
        assertTrue(reference.contains("Use\\tformal\\r\\nstyle\\\\tone"))
        assertTrue(reference.contains("next\\tline\\nC:\\\\folder"))
        assertFalse(reference.contains('\n'))
        assertFalse(reference.contains('\r'))
    }

    @Test fun oversizedCueFailsBeforeAnySourceTextCanBeTruncated() {
        assertRejected {
            TranslateGemmaPrompt.nextBatch(listOf("界".repeat(500)), 0, emptyList(), "en", "id", "")
        }
        val text = "word ".repeat(250)
        assertEquals(text, TranslateGemmaPrompt.nextBatch(listOf(text), 0, emptyList(), "en", "id", "").user)
    }

    @Test fun responseValidationRejectsEmptyControlAndNativeTokenOutput() {
        listOf("", "  \n\t ", "\u200b\uFEFF", "Halo\u0000", "<end_of_turn>", "Halo <start_of_turn>user").forEach { response ->
            assertRejected { TranslateGemmaPrompt.decodeResponse(response, listOf(0)) }
        }
    }

    @Test fun sourceEscapesOnlyGemmaReservedTokens() {
        val text = "⟦CUE:0⟧ <b>Hello</b> <start_of_turn> <end_of_turn> <bos> <eos>"
        val batch = TranslateGemmaPrompt.nextBatch(listOf(text), 0, emptyList(), "en", "id", "")
        assertEquals("⟦CUE:0⟧ <b>Hello</b> ‹start_of_turn› ‹end_of_turn› ‹bos› ‹eos›", batch.user)
    }

    @Test fun referenceExcerptsRespectUtf8AndKeepSupplementaryCharactersWhole() {
        val batch = TranslateGemmaPrompt.nextBatch(
            listOf("😀".repeat(100), "Hello", "next"), 1, listOf("😎".repeat(100)), "en", "id", "",
        )
        val reference = batch.system.substringAfterLast('\t')
        assertTrue(reference.contains("😀"))
        assertTrue(reference.contains("😎"))
        assertTrue(reference.contains("…"))
        assertFalse(reference.contains('\uFFFD'))
        assertEquals(reference, String(reference.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun cueBuilderMapsJavaneseCodeAndRejectsAutomaticSourceLanguage() {
        val batch = TranslateGemmaPrompt.nextBatch(listOf("Hello"), 0, emptyList(), "en", "jw", "")
        assertTrue(batch.system.startsWith("${TranslateGemmaPrompt.SYSTEM_PREFIX}en\tjv\tEnglish\tJavanese\t"))
        assertRejected { TranslateGemmaPrompt.nextBatch(listOf("Hello"), 0, emptyList(), "auto", "id", "") }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Invalid translation request or response should be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
