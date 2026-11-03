package id.tomz.transcriptor.translation

import id.tomz.transcriptor.domain.TranslationLanguages
import id.tomz.transcriptor.inference.NativeInferenceWorker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class TextTranslator @Inject constructor(
    private val settings: AiTranslationSettings,
    private val cloud: CloudAiClient,
    private val local: NativeInferenceWorker,
    private val offlineModels: OfflineModelStore,
) {
    private val mutex = Mutex()

    @Suppress("UNUSED_PARAMETER") // Compatibility with callers predating AI translation.
    suspend fun translate(
        texts: List<String>,
        source: String,
        target: String,
        allowCellular: Boolean,
        onModelReady: suspend () -> Unit,
        onProgress: (Int) -> Unit,
    ): List<String> = mutex.withLock {
        TranslationLanguages.validate(source, target)
        require(texts.isNotEmpty() && texts.all { it.isNotBlank() }) { "Transkrip belum tersedia atau berisi segmen kosong" }
        val config = withContext(Dispatchers.IO) { settings.read() }
        val offline = config.mode == AiTranslationMode.OFFLINE
        val selectedModelId = if (offline) offlineModels.selectedModelId else null
        val modelPath = if (offline) {
            offlineModels.verifiedModelFile()?.absolutePath
                ?: throw IllegalStateException("Unduh dan aktifkan model pada halaman Model → Penerjemah AI sebelum menerjemahkan offline")
        } else {
            CloudAiClient.chatCompletionsUrl(config.endpoint)
            require(config.apiKey.isNotBlank()) { "Isi kunci API di pengaturan AI cloud" }
            null
        }
        currentCoroutineContext().ensureActive()
        val metadataStart = android.os.SystemClock.elapsedRealtime()
        val translateGemma = offline && (selectedModelId == "ai-translategemma-4b" ||
            withContext(Dispatchers.IO) { GgufTranslationModel.isTranslateGemma(java.io.File(requireNotNull(modelPath))) })
        if (offline) id.tomz.transcriptor.diagnostics.AppDiagnostics.event("translation.metadata_inspected",
            mapOf("duration_ms" to android.os.SystemClock.elapsedRealtime() - metadataStart))
        onModelReady()
        local.withSession { session ->
            val translated = mutableListOf<String>()
            while (translated.size < texts.size) {
                currentCoroutineContext().ensureActive()
                if (translateGemma) {
                    val batch = TranslateGemmaPrompt.nextBatch(texts, translated.size, translated, source, target, config.guidance)
                    val response = session.generate(requireNotNull(modelPath), batch.system, batch.user) { /* Progress counts validated cues only. */ }
                    currentCoroutineContext().ensureActive()
                    translated += TranslateGemmaPrompt.decodeResponse(response, batch.cueIds)
                } else {
                    val batch = ContextTranslation.nextBatch(texts, translated.size, translated, source, target, config.guidance, offline)
                    val response = if (offline) {
                        session.generate(requireNotNull(modelPath), batch.system, batch.user, grammar = ContextTranslation.responseGrammar(batch.cueIds)) { /* Progress counts validated cues only. */ }
                    } else cloud.generate(config, batch.system, batch.user)
                    currentCoroutineContext().ensureActive()
                    // Accept a batch only after full alignment validation. Callers receive a result only when every batch passes.
                    translated += ContextTranslation.decodeResponse(response, batch.cueIds)
                }
                onProgress(translated.size * 100 / texts.size)
            }
            translated.toList()
        }
    }

    fun cancel() { cloud.cancel(); local.cancel() }
}
