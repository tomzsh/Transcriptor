package id.tomz.transcriptor.model

import org.junit.Assert.*
import org.junit.Test

class AiModelCatalogTest {
    @Test fun catalogOffersPinnedQwenTranslateGemmaGemma4AndLlamaDownloads() {
        val models = ModelCatalog.translationModels
        assertEquals(listOf("ai-qwen35-0.8b", "ai-qwen35-2b", "ai-translategemma-4b", "ai-gemma4-e2b", "ai-llama32-1b"), models.map { it.id })
        assertTrue(models.all { it.kind == ModelKind.AI_TRANSLATION && it.fileName.endsWith(".gguf") })
        assertTrue(models.all { it.upstreamUrl.matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+\\.gguf")) })
        assertTrue(models.all { it.bytes in 1_048_576..4L * 1024 * 1024 * 1024 })
        assertTrue(models.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.downloadBytes == it.bytes && it.downloadSha256 == it.sha256 })
        assertTrue(models.all { it.description.isNotBlank() && it.licenceUrl?.startsWith("https://") == true })
        assertEquals(532_517_120L, models.first().bytes)
        assertEquals("bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", models.first().sha256)
        val translateGemma = models.first { it.id == "ai-translategemma-4b" }
        assertEquals("translategemma-4b-it.Q4_K_M.gguf", translateGemma.fileName)
        assertEquals(2_489_909_760L, translateGemma.bytes)
        assertEquals("81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330", translateGemma.sha256)
        assertTrue(translateGemma.upstreamUrl.contains("/mradermacher/translategemma-4b-it-GGUF/resolve/d37868f759d2f817a43d28dd80cf9f982ffa62fd/"))
        assertEquals("https://ai.google.dev/gemma/terms", translateGemma.licenceUrl)
        val gemma4 = models.first { it.id == "ai-gemma4-e2b" }
        assertEquals("gemma-4-E2B_q4_0-it.gguf", gemma4.fileName)
        assertEquals(3_349_516_256L, gemma4.bytes)
        assertEquals("fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", gemma4.sha256)
        assertTrue(gemma4.upstreamUrl.contains("/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/"))
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.distinct().size)
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.distinct().size)
    }
}
