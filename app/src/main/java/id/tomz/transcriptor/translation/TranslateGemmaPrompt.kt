package id.tomz.transcriptor.translation

import java.util.Locale

/**
 * TranslateGemma translates one piece of text, rather than following a JSON/marker protocol.
 * A request therefore owns exactly one original cue; the entire answer belongs to that cue.
 * Reference dialogue travels separately from the text to translate in the native adapter.
 */
internal object TranslateGemmaPrompt {
    const val SYSTEM_PREFIX = "__TRANSLATEGEMMA_V2__\t"
    private const val MAX_CUE_BYTES = 1_300
    private const val MAX_INPUT_BYTES = 3_200
    private val reservedToken = Regex(
        "<(?:pad|eos|bos|unk|mask|start_of_turn|end_of_turn|start_of_image|end_of_image|image_soft_token|" +
            "start_of_audio|end_of_audio|audio_soft_token|audio_text|audio_perception|text_sentinel|" +
            "audio_sentinel|soft_audio_text|unused\\d+)>",
    )

    fun nextBatch(
        texts: List<String>,
        start: Int,
        previousTranslations: List<String>,
        source: String,
        target: String,
        guidance: String,
    ): TranslationBatch {
        require(start in texts.indices && previousTranslations.size == start) { "Urutan konteks terjemahan tidak valid" }
        val sourceLanguage = language(source)
        val targetLanguage = language(target)
        require(sourceLanguage.code != targetLanguage.code) { "Bahasa asal dan tujuan harus berbeda" }
        require(guidance.toByteArray(Charsets.UTF_8).size <= 400) { "Arahan AI offline maksimal 400 byte" }
        require(validText(texts[start]) && hasVisibleText(texts[start])) {
            "Segmen transkrip kosong atau berisi karakter kontrol tidak valid; perbaiki sebelum menerjemahkan"
        }
        val cue = safe(texts[start])
        // Never excerpt or split the cue: its timing and source/translation association are indivisible.
        require(cue.toByteArray(Charsets.UTF_8).size <= MAX_CUE_BYTES) {
            "Segmen ${start + 1} terlalu panjang untuk TranslateGemma; pisahkan segmen sebelum menerjemahkan"
        }
        require(validText(guidance)) { "Arahan AI berisi karakter kontrol tidak valid" }
        val reference = buildString {
            if (guidance.isNotBlank()) append("Translation guidance (terminology and tone): ${safe(guidance)}\n")
            val before = (maxOf(0, start - 2) until start).map { index ->
                "Source: ${excerpt(texts[index], 128)}\nExisting translation: ${excerpt(previousTranslations[index], 128)}"
            }
            if (before.isNotEmpty()) append("Previous dialogue:\n${before.joinToString("\n")}\n")
            val after = (start + 1 until minOf(texts.size, start + 3)).map { index -> excerpt(texts[index], 160) }
            if (after.isNotEmpty()) append("Following dialogue:\n${after.joinToString("\n")}\n")
        }
        val metadata = "$SYSTEM_PREFIX${sourceLanguage.code}\t${targetLanguage.code}\t" +
            "${sourceLanguage.name}\t${targetLanguage.name}\t${escapeReference(reference)}"
        require((metadata + cue).toByteArray(Charsets.UTF_8).size <= MAX_INPUT_BYTES) {
            "Konteks TranslateGemma terlalu panjang; persingkat arahan sebelum menerjemahkan"
        }
        return TranslationBatch(listOf(start), metadata, cue)
    }

    fun decodeResponse(response: String, expectedIds: List<Int>): List<String> {
        require(expectedIds.size == 1 && expectedIds.single() >= 0) {
            "TranslateGemma harus menerjemahkan tepat satu segmen per permintaan"
        }
        require(response.length <= 16_000 && validText(response) && hasVisibleText(response)) {
            "TranslateGemma menghasilkan terjemahan kosong, terlalu panjang, atau tidak valid; terjemahan tidak disimpan"
        }
        require(!reservedToken.containsMatchIn(response)) {
            "TranslateGemma menghasilkan token kontrol; terjemahan tidak disimpan"
        }
        // No marker extraction, punctuation splitting, or guessed cue count can misalign this answer.
        return listOf(response.trim())
    }

    private data class Language(val code: String, val name: String)

    private fun language(raw: String): Language {
        var code = raw.trim().lowercase(Locale.ROOT).replace('_', '-')
        require(code != "auto" && code.matches(Regex("[a-z]{2,3}(?:-[a-z]{2})?"))) {
            "TranslateGemma memerlukan bahasa asal dan tujuan yang dipilih"
        }
        if (code == "jw") code = "jv"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        require(name.isNotBlank() && !name.equals(code, ignoreCase = true)) {
            "Kode bahasa $raw tidak didukung TranslateGemma"
        }
        return Language(code, name)
    }

    // Ordinary markup and former cue-marker text are legitimate dialogue. Only native control
    // tokens need escaping because the complete Gemma chat prompt parses special tokens.
    private fun safe(text: String): String = reservedToken.replace(text) {
        "‹${it.value.substring(1, it.value.length - 1)}›"
    }

    /** The V2 native protocol has five tab-separated fields; only the reference field is escaped. */
    private fun escapeReference(text: String): String = buildString {
        for (char in text) when (char) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }

    private fun validText(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isISOControl(char) && char != '\n' && char != '\r' && char != '\t') return false
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
                index++
            } else if (Character.isLowSurrogate(char)) return false
            index++
        }
        return true
    }

    private fun hasVisibleText(text: String): Boolean = text.any {
        !it.isWhitespace() && Character.getType(it) != Character.FORMAT.toInt() && !Character.isISOControl(it)
    }

    private fun excerpt(text: String, maxBytes: Int): String {
        require(validText(text)) { "Konteks transkrip berisi karakter kontrol tidak valid" }
        val sanitized = safe(text)
        if (sanitized.toByteArray(Charsets.UTF_8).size <= maxBytes) return sanitized
        var bytes = 0
        var end = 0
        while (end < sanitized.length) {
            val point = sanitized.codePointAt(end)
            val size = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            if (bytes + size > maxBytes - 3) break
            bytes += size
            end += Character.charCount(point)
        }
        return sanitized.substring(0, end) + "…"
    }
}
