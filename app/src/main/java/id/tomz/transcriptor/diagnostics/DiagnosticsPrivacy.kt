package id.tomz.transcriptor.diagnostics

/** Only developer supplied event codes and explicitly approved operational scalars enter the log. */
internal object DiagnosticsPrivacy {
    private val codePattern = Regex("[a-z][a-z0-9_.]{0,63}")
    private val symbolPattern = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,159}")
    private val methodPattern = Regex("[A-Za-z_$<>][A-Za-z0-9_$<>-]{0,79}")
    private val numbers = setOf(
        "duration_ms", "elapsed_ms", "threads", "cpu_count", "segment_count", "token_count",
        "frame_count", "audio_ms", "model_bytes", "available_bytes", "total_bytes", "java_used_bytes",
        "java_max_bytes", "native_heap_bytes", "pss_kb", "rss_kb", "thermal_status", "reason", "status",
        "exit_timestamp_ms", "sample_rate", "channels", "queue_depth", "attempt", "progress_percent",
        "memory_limit_bytes", "input_bytes", "output_bytes", "pid", "error_code",
    )
    private val booleans = setOf("success", "cancelled", "low_memory", "enabled", "supported")
    private val enums = mapOf(
        "stage" to setOf("idle", "importing", "decoding", "transcribing", "diarizing", "translating", "exporting", "loading", "processing", "completed", "failed", "cancelled"),
        "backend" to setOf("cpu", "gpu", "vulkan", "nnapi", "auto", "cloud", "local", "whisper", "sherpa", "llama"),
        "state" to setOf("started", "running", "completed", "failed", "cancelled", "interrupted", "connected", "disconnected", "ready", "stopped"),
        "operation" to setOf("transcription", "diarization", "translation", "model_load", "model_download", "media_decode", "export", "import", "startup", "bind", "inference"),
        "process" to setOf("main", "inference", "speaker", "other"),
    )

    fun code(value: String): String = value.takeIf { codePattern.matches(it) } ?: "invalid_event"

    fun fields(input: Map<String, Any?>): Map<String, Any> = buildMap {
        input.entries.take(32).forEach { (key, value) ->
            when {
                key in numbers && value is Byte -> put(key, value)
                key in numbers && value is Short -> put(key, value)
                key in numbers && value is Int -> put(key, value)
                key in numbers && value is Long -> put(key, value)
                key in numbers && value is Float && value.isFinite() -> put(key, value)
                key in numbers && value is Double && value.isFinite() -> put(key, value)
                key in booleans && value is Boolean -> put(key, value)
                value is String && value in enums[key].orEmpty() -> put(key, value)
            }
        }
    }

    fun failure(error: Throwable): Map<String, Any> = buildMap {
        put("exception_class", symbol(error.javaClass.name))
        // Never call message, localizedMessage, toString, printStackTrace, or read a source file name.
        put("frames", error.stackTrace.take(12).mapNotNull { frame ->
            if (!symbolPattern.matches(frame.className) || !methodPattern.matches(frame.methodName)) null
            else "${frame.className}.${frame.methodName}:${frame.lineNumber.coerceAtLeast(-1)}"
        })
        val causes = mutableListOf<String>()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        seen[error] = true
        var cause = error.cause
        while (cause != null && causes.size < 4 && seen.put(cause, true) == null) {
            causes += symbol(cause.javaClass.name)
            cause = cause.cause
        }
        if (causes.isNotEmpty()) put("cause_classes", causes)
    }

    fun process(name: String?, packageName: String): String = when (name) {
        packageName -> "main"
        "$packageName:inference" -> "inference"
        "$packageName:speaker_diarization" -> "speaker"
        else -> "other"
    }

    fun exitFields(name: String?, packageName: String, reason: Int, status: Int, pss: Long, rss: Long, timestamp: Long): Map<String, Any> = mapOf(
        "process" to process(name, packageName), "reason" to reason, "status" to status,
        "pss_kb" to pss.coerceAtLeast(0), "rss_kb" to rss.coerceAtLeast(0), "exit_timestamp_ms" to timestamp,
    )

    private fun symbol(value: String): String = value.takeIf { symbolPattern.matches(it) } ?: "unknown"
}

/** Small JSON writer so the storage and privacy boundary can be tested on the JVM, without Android. */
internal object DiagnosticsJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
                }
            }
            append('"')
        }
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> "${encode(key as String)}:${encode(item)}" }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> "null"
    }
}
