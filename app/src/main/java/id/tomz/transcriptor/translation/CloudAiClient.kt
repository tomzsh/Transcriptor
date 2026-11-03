package id.tomz.transcriptor.translation

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** One authenticated HTTPS request per batch; never follows redirects or exposes provider bodies in errors. */
@Singleton
class CloudAiClient @Inject constructor() {
    private val requests = ConcurrentHashMap.newKeySet<CancellableContinuation<String>>()

    suspend fun generate(config: AiTranslationConfig, system: String, user: String): String {
        val endpoint = chatCompletionsUrl(config.endpoint)
        require(config.apiKey.isNotBlank() && config.apiKey.length <= 4096 && config.apiKey.all { it.code in 33..126 }) {
            "Isi kunci API cloud yang valid di pengaturan AI"
        }
        require(config.model.isNotBlank() && config.model.length <= 200 && config.model.none { it.isISOControl() }) {
            "Isi ID model AI cloud yang valid"
        }
        val body = requestBody(config, system, user)
        require(body.size <= 65536) { "Permintaan AI terlalu besar" }
        return try { withTimeout(120000L) {
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                requests += continuation
                val worker = CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                    try {
                        currentCoroutineContext().ensureActive()
                        val client = URL(endpoint).openConnection() as HttpURLConnection
                        connection.set(client)
                        currentCoroutineContext().ensureActive()
                        client.instanceFollowRedirects = false
                        client.connectTimeout = 15000
                        client.readTimeout = 30000
                        client.requestMethod = "POST"
                        client.doOutput = true
                        client.useCaches = false
                        client.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                        client.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        client.setRequestProperty("Accept", "application/json")
                        client.setFixedLengthStreamingMode(body.size)
                        client.outputStream.use { it.write(body) }
                        val status = client.responseCode
                        require(status in 200..299) {
                            when (status) {
                                in 300..399 -> "Endpoint AI mengalihkan permintaan; gunakan URL HTTPS tujuan secara langsung"
                                401, 403 -> "Kunci API ditolak; periksa kunci dan akses model cloud"
                                402 -> "Saldo atau kuota API cloud tidak cukup"
                                429 -> "Batas permintaan API tercapai; coba lagi nanti"
                                else -> "Layanan AI gagal (HTTP $status); periksa endpoint dan model dengan dukungan JSON"
                            }
                        }
                        require(client.contentLengthLong <= 1048576) { "Respons AI terlalu besar" }
                        val output = ByteArrayOutputStream()
                        client.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 1048576) { "Respons AI terlalu besar" }
                                output.write(buffer, 0, count)
                            }
                        }
                        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        val result = decodeCompletion(json)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: CancellationException) {
                        continuation.cancel(error)
                    } catch (error: IllegalArgumentException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Tidak dapat menghubungi API AI; periksa koneksi dan endpoint HTTPS"),
                        )
                    } finally {
                        requests -= continuation
                        runCatching { connection.get()?.disconnect() }
                    }
                }
                continuation.invokeOnCancellation {
                    worker.cancel()
                    runCatching { connection.get()?.disconnect() }
                }
                worker.invokeOnCompletion { requests -= continuation }
            }
        } } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("Waktu tunggu API AI habis; coba lagi atau pilih model yang lebih cepat")
        }
    }

    fun cancel() { requests.forEach { it.cancel(CancellationException("Terjemahan dibatalkan")) } }

    companion object {
        internal fun requestBody(config: AiTranslationConfig, system: String, user: String): ByteArray {
            val fields = linkedMapOf<String, Any?>(
                "model" to config.model, "stream" to false, "max_tokens" to 4096,
                "response_format" to mapOf("type" to "json_object"),
                "messages" to listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user)),
            )
            // Provider-specific controls stay off other OpenAI-compatible endpoints.
            if (URI(config.endpoint).host.equals("openrouter.ai", ignoreCase = true) && config.model.startsWith("qwen/qwen3.5")) {
                fields["reasoning"] = mapOf("enabled" to false)
            }
            return TranslationJson.stringify(fields).toByteArray(Charsets.UTF_8)
        }

        fun chatCompletionsUrl(endpoint: String): String {
            val text = endpoint.trim().trimEnd('/')
            val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("Endpoint AI harus berupa URL HTTPS yang valid") }
            require(text.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65535)) { "Endpoint AI harus HTTPS tanpa kredensial, query atau fragmen" }
            return if (uri.path.endsWith("/chat/completions")) text else "$text/chat/completions"
        }

        /** Validates transport completion before the cue-alignment decoder sees the model text. */
        fun decodeCompletion(response: String): String {
            val root = TranslationJson.parse(response) as? Map<*, *>
                ?: throw IllegalArgumentException("Respons API AI tidak valid")
            require("error" !in root) { "API AI melaporkan kegagalan; periksa model dan kuota" }
            val choices = root["choices"] as? List<*>
            require(choices?.size == 1) { "Respons API AI tidak berisi satu hasil lengkap" }
            val choice = choices.single() as? Map<*, *> ?: throw IllegalArgumentException("Hasil API AI tidak valid")
            require(choice["finish_reason"] == "stop") { "Respons AI tidak selesai atau dibatasi; gunakan model lain atau segmen lebih pendek" }
            val message = choice["message"] as? Map<*, *> ?: throw IllegalArgumentException("Pesan API AI tidak valid")
            require(message["role"] == "assistant" && message["refusal"] == null) { "AI tidak mengembalikan terjemahan" }
            val content = message["content"] as? String
            require(!content.isNullOrBlank()) { "Terjemahan API AI kosong" }
            return content
        }
    }
}
