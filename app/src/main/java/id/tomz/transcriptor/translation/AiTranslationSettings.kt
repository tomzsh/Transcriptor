package id.tomz.transcriptor.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

enum class AiTranslationMode { CLOUD, OFFLINE }

data class AiTranslationConfig(
    val mode: AiTranslationMode = AiTranslationMode.CLOUD,
    val endpoint: String = "https://openrouter.ai/api/v1",
    val model: String = "qwen/qwen3.5-27b",
    val apiKey: String = "",
    val guidance: String = "",
) {
    // Avoid accidental disclosure if a configuration object is included in a diagnostic message.
    override fun toString(): String = "AiTranslationConfig(mode=$mode, model=$model, apiKey=[redacted])"
}

/** Nonsecret preferences plus a Keystore-encrypted secret in Android's excluded-from-backup directory. */
@Singleton
class AiTranslationSettings @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("ai_translation", Context.MODE_PRIVATE)
    private val keyFile = AtomicFile(File(context.noBackupFilesDir, "ai-translation-key.enc"))

    @Synchronized
    fun read(): AiTranslationConfig {
        val defaults = AiTranslationConfig()
        return AiTranslationConfig(
            mode = runCatching { AiTranslationMode.valueOf(preferences.getString("mode", "CLOUD")!!) }.getOrDefault(AiTranslationMode.CLOUD),
            endpoint = preferences.getString("endpoint", defaults.endpoint) ?: defaults.endpoint,
            model = preferences.getString("model", defaults.model) ?: defaults.model,
            apiKey = readKey(),
            guidance = preferences.getString("guidance", "") ?: "",
        )
    }

    @Synchronized
    fun save(config: AiTranslationConfig) {
        val normalized = config.copy(endpoint = config.endpoint.trim(), model = config.model.trim(), apiKey = config.apiKey.trim(), guidance = config.guidance.trim())
        if (normalized.mode == AiTranslationMode.CLOUD) {
            CloudAiClient.chatCompletionsUrl(normalized.endpoint)
            require(normalized.model.isNotBlank() && normalized.model.length <= 200 && normalized.model.none { it.isISOControl() }) { "ID model AI tidak valid" }
        }
        require(normalized.apiKey.length <= 4096 && normalized.apiKey.all { it.code in 33..126 }) { "Kunci API tidak valid" }
        require(normalized.guidance.toByteArray(Charsets.UTF_8).size <= if (normalized.mode == AiTranslationMode.OFFLINE) 400 else 1000) {
            "Arahan terlalu panjang; maksimal ${if (normalized.mode == AiTranslationMode.OFFLINE) 400 else 1000} byte"
        }
        writeKey(normalized.apiKey)
        check(preferences.edit().putString("mode", normalized.mode.name).putString("endpoint", normalized.endpoint)
            .putString("model", normalized.model).putString("guidance", normalized.guidance).commit()) {
            "Pengaturan AI tidak dapat disimpan"
        }
    }

    private fun readKey(): String {
        if (!keyFile.baseFile.exists()) return ""
        try {
            val bytes = keyFile.openRead().use { it.readBytes() }
            require(bytes.size in 30..8192 && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("Kunci API tersimpan tidak dapat dibaca; simpan ulang kunci di pengaturan AI")
        }
    }

    private fun writeKey(value: String) {
        if (value.isEmpty()) { keyFile.delete(); return }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val stream = keyFile.startWrite()
            try { stream.write(encrypted); keyFile.finishWrite(stream) }
            catch (error: Exception) { keyFile.failWrite(stream); throw error }
        } catch (_: Exception) {
            throw IllegalStateException("Kunci API tidak dapat disimpan dengan aman; coba lagi")
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "transcriptor.translation.api-key.v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
