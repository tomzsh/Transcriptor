package id.tomz.transcriptor.translation

import java.io.File
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.Locale

/** Identifies translation-only imports from GGUF metadata without creating an inference model. */
internal object GgufTranslationModel {
    fun isTranslateGemma(file: File): Boolean = runCatching {
        DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { input ->
            val reader = Metadata(input, file.length())
            require(reader.int() == 0x46554747)
            require(reader.int() in 2..3)
            require(reader.long() >= 0) // Tensor count; weights are never read.
            val count = reader.long().also { require(it in 0..4096) }
            var found = false
            repeat(count.toInt()) {
                val key = reader.string(512)
                val type = reader.int()
                if (type == 8 && key in setOf("general.name", "general.basename", "general.finetune", "tokenizer.chat_template")) {
                    val value = reader.string(512 * 1024).lowercase(Locale.ROOT)
                    found = found || if (key == "tokenizer.chat_template") {
                        "source_lang_code" in value && "target_lang_code" in value && "translator" in value
                    } else Regex("(?:^|[^a-z])translategemma(?:[^a-z]|$)").containsMatchIn(value)
                } else reader.skipValue(type, 0)
            }
            found
        }
    }.getOrDefault(false)

    private class Metadata(private val input: DataInputStream, private val length: Long) {
        private var position = 0L
        private fun take(bytes: Long) {
            require(bytes >= 0 && bytes <= minOf(LIMIT, length) - position)
            position += bytes
        }
        fun int(): Int { take(4); return Integer.reverseBytes(input.readInt()) }
        fun long(): Long { take(8); return java.lang.Long.reverseBytes(input.readLong()) }
        fun string(max: Int): String {
            val size = long().also { require(it in 0..max.toLong()); take(it) }
            return ByteArray(size.toInt()).also(input::readFully).toString(Charsets.UTF_8)
        }
        fun skipValue(type: Int, depth: Int) {
            require(depth <= 4)
            val bytes = when (type) {
                0, 1, 7 -> 1L
                2, 3 -> 2L
                4, 5, 6 -> 4L
                10, 11, 12 -> 8L
                8 -> long()
                9 -> {
                    val element = int()
                    val count = long().also { require(it in 0..500_000) }
                    repeat(count.toInt()) { skipValue(element, depth + 1) }
                    return
                }
                else -> error("Unsupported GGUF metadata type")
            }
            take(bytes)
            var remaining = bytes
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else { input.readByte(); remaining-- }
            }
        }
        companion object { const val LIMIT = 16L * 1024 * 1024 }
    }
}
