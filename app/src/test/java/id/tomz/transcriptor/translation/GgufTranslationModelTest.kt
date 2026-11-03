package id.tomz.transcriptor.translation

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufTranslationModelTest {
    @Test fun renamedImportUsesNativeProtocolFromItsModelMetadata() = withFixture {
        it.writeBytes(gguf("general.name" to "google/TranslateGemma-4b-it"))
        assertTrue(GgufTranslationModel.isTranslateGemma(it))
    }

    @Test fun translationSpecificChatTemplateIsRecognizedWithoutDependingOnFileName() = withFixture {
        it.writeBytes(gguf(
            "general.name" to "Custom local translation model",
            "tokenizer.chat_template" to "You are a professional translator {{ source_lang_code }} {{ target_lang_code }}",
        ))
        assertTrue(GgufTranslationModel.isTranslateGemma(it))
    }

    @Test fun ordinaryGemmaAndUnrelatedTemplateRemainOnGenericProtocol() = withFixture {
        it.writeBytes(gguf("general.name" to "Gemma-3-4b-it", "tokenizer.chat_template" to "<start_of_turn>user\n"))
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
        it.writeBytes(gguf("general.description" to "An alternative to TranslateGemma"))
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
    }

    @Test fun arbitraryFileContentAndTruncatedOrHugeHeadersCannotSelectNativeProtocol() = withFixture {
        it.writeText("TranslateGemma")
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
        val bytes = gguf("general.name" to "TranslateGemma")
        it.writeBytes(bytes.copyOf(bytes.size - 1))
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, Long.MAX_VALUE)
        it.writeBytes(bytes)
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
    }

    @Test fun metadataCanFollowVocabularyArraysWithoutLoadingModelWeights() = withFixture {
        val out = ByteArrayOutputStream()
        fun int(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun long(value: Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun string(value: String) { val bytes = value.toByteArray(); long(bytes.size.toLong()); out.write(bytes) }
        out.write(byteArrayOf(0x47, 0x47, 0x55, 0x46)); int(3); long(0); long(2)
        string("tokenizer.ggml.tokens"); int(9); int(8); long(3)
        string("hello"); string("world"); string("TranslateGemma is just an ordinary token")
        string("general.basename"); int(8); string("TranslateGemma")
        it.writeBytes(out.toByteArray())
        assertTrue(GgufTranslationModel.isTranslateGemma(it))
    }

    @Test fun vocabularyTextAloneCannotIdentifyModelFamily() = withFixture {
        it.writeBytes(gguf("tokenizer.chat_template" to "TranslateGemma"))
        assertFalse(GgufTranslationModel.isTranslateGemma(it))
    }

    private fun withFixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("translation-model-family").toFile()
        try { block(File(directory, "arbitrary-import-name.gguf")) } finally { directory.deleteRecursively() }
    }

    private fun gguf(vararg metadata: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(value: Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun long(value: Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun string(value: String) { val bytes = value.toByteArray(); long(bytes.size.toLong()); out.write(bytes) }
        out.write(byteArrayOf(0x47, 0x47, 0x55, 0x46)); int(3); long(0); long(metadata.size.toLong())
        for ((key, value) in metadata) { string(key); int(8); string(value) }
        return out.toByteArray()
    }
}
