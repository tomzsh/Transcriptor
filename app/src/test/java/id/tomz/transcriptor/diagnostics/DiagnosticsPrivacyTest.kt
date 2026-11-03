package id.tomz.transcriptor.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPrivacyTest {
    @Test fun dropsUnapprovedFieldsAndFreeTextEvenUnderAnApprovedKey() {
        val fields = DiagnosticsPrivacy.fields(mapOf(
            "duration_ms" to 125L,
            "threads" to 2,
            "stage" to "transcribing",
            "backend" to "a private transcript sk-secret",
            "message" to "sk-secret /private/audio.wav",
            "transcript" to "private conversation",
            "model_bytes" to "/private/model.bin",
            "success" to true,
            "elapsed_ms" to Double.NaN,
        ))
        assertEquals(mapOf("duration_ms" to 125L, "threads" to 2, "stage" to "transcribing", "success" to true), fields)
        assertEquals("invalid_event", DiagnosticsPrivacy.code("https://private/key"))
    }

    @Test fun failureContainsOnlyBoundedCodeFramesWithoutMessagesOrSourcePaths() {
        val failure = IllegalStateException("private transcript sk-secret", RuntimeException("Bearer private"))
        failure.stackTrace = Array(40) { StackTraceElement("id.tomz.Worker", "run", "/private/audio.wav", it + 1) }
        val json = DiagnosticsJson.encode(DiagnosticsPrivacy.failure(failure))
        assertTrue(json.contains("java.lang.IllegalStateException"))
        assertTrue(json.contains("id.tomz.Worker.run:1"))
        assertFalse(json.contains("private"))
        assertFalse(json.contains("audio.wav"))
        assertFalse(json.contains("run:13"))
        assertTrue(json.length < 4_096)
    }

    @Test fun exitMetadataMapsOnlyKnownProcessesAndNeverAcceptsFreeText() {
        val fields = DiagnosticsPrivacy.exitFields("other.app:private_title", "id.tomz.transcriptor", 5, 11, 20, 30, 100)
        assertEquals("other", fields["process"])
        assertEquals(5, fields["reason"])
        assertEquals(11, fields["status"])
        assertEquals(20L, fields["pss_kb"])
        assertEquals("speaker", DiagnosticsPrivacy.process("id.tomz.transcriptor:speaker_diarization", "id.tomz.transcriptor"))
        assertFalse(DiagnosticsJson.encode(fields).contains("private_title"))
    }
}
