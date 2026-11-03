package id.tomz.transcriptor.translation

import org.junit.Assert.*
import org.junit.Test

class CloudAiClientTest {
    @Test fun defaultCloudRequestUsesQwen35WithoutReasoningOrApiKeyInTheBody() {
        val secret = "test-private-api-key"
        val serialized = CloudAiClient.requestBody(AiTranslationConfig(apiKey = secret), "Translate", "Subtitle")
        assertFalse(serialized.toString(Charsets.UTF_8).contains(secret))
        val payload = TranslationJson.parse(serialized.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals("qwen/qwen3.5-27b", payload["model"])
        assertEquals(mapOf("enabled" to false), payload["reasoning"])
    }

    @Test fun reasoningControlIsLimitedToOpenRouterQwen35Requests() {
        listOf(
            AiTranslationConfig(endpoint = "https://api.example.com/v1", model = "qwen/qwen3.5-27b"),
            AiTranslationConfig(endpoint = "https://openrouter.ai.attacker.example/v1", model = "qwen/qwen3.5-27b"),
            AiTranslationConfig(endpoint = "https://openrouter.ai/api/v1", model = "qwen/qwen3-0.6b"),
            AiTranslationConfig(endpoint = "https://openrouter.ai/api/v1", model = "openai/gpt-4.1-mini"),
        ).forEach { config ->
            val payload = TranslationJson.parse(CloudAiClient.requestBody(config, "Translate", "Subtitle").toString(Charsets.UTF_8)) as Map<*, *>
            assertFalse(payload.containsKey("reasoning"))
        }
    }

    @Test fun httpsBaseUrlsBecomeChatCompletionEndpoints() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", CloudAiClient.chatCompletionsUrl("https://openrouter.ai/api/v1/"))
        assertEquals("https://api.example.com/v1/chat/completions", CloudAiClient.chatCompletionsUrl("https://api.example.com/v1/chat/completions"))
    }

    @Test fun credentialsCannotTravelToInsecureOrAmbiguousEndpoints() {
        listOf("http://example.com/v1", "https://user:password@example.com/v1", "https://example.com/v1?key=secret", "https://example.com/v1#secret", "https:///v1", "file:///tmp/test").forEach {
            rejected { CloudAiClient.chatCompletionsUrl(it) }
        }
    }

    @Test fun onlyACompleteAssistantTextIsAccepted() {
        assertEquals("{\"translations\":[]}", CloudAiClient.decodeCompletion("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"translations\":[]}"}}]}"""))
        listOf(
            """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"content_filter","message":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":" "}}]}""",
            """{"choices":[],"error":{"message":"secret API key"}}""",
        ).forEach { rejected { CloudAiClient.decodeCompletion(it) } }
    }

    @Test fun providerErrorTextNeverEscapesThroughExceptionMessages() {
        try {
            CloudAiClient.decodeCompletion("""{"error":{"message":"secret-transcript-and-key"}}""")
            fail("Expected provider error")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.toString().contains("secret-transcript-and-key"))
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid response/endpoint to be rejected") }
        catch (_: IllegalArgumentException) { }
    }

}
