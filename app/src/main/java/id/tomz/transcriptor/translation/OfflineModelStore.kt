package id.tomz.transcriptor.translation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import id.tomz.transcriptor.model.VerifiedModelInstaller
import id.tomz.transcriptor.model.localModelDirectory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal const val MAX_OFFLINE_MODEL_BYTES = 4L * 1024 * 1024 * 1024
internal const val MIN_OFFLINE_MODEL_BYTES = 1024L * 1024
private const val DISK_RESERVE_BYTES = 128L * 1024 * 1024

// Serializes validation/import in each process. Imports replace files atomically;
// an inference worker keeps its existing mmap until that session is released.
internal object OfflineAiAccess {
    val lock = ReentrantLock(true)
}

internal fun validateOfflineGguf(file: File) {
    require(file.isFile && file.length() in MIN_OFFLINE_MODEL_BYTES..MAX_OFFLINE_MODEL_BYTES) {
        "Ukuran model AI harus antara 1 MB dan 4 GiB"
    }
    val header = ByteArray(8)
    FileInputStream(file).use { input ->
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            require(read > 0) { "Berkas model GGUF tidak lengkap" }
            count += read
        }
    }
    require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
        "Berkas bukan model GGUF. Pilih model instruct/chat berformat .gguf"
    }
    require(header[4].toInt() in 2..3 && header.drop(5).all { it == 0.toByte() }) {
        "Versi model GGUF tidak didukung; gunakan GGUF versi 2 atau 3"
    }
}

@Singleton
class OfflineModelStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val directory: File get() = File(context.noBackupFilesDir, "offline-ai")
    private val installedFile: File get() = File(directory, "model.gguf")

    private val preferences = context.getSharedPreferences("offline_ai_models", Context.MODE_PRIVATE)
    val importedModel: File? get() = installedFile.takeIf { runCatching { validateOfflineGguf(it) }.isSuccess }
    val selectedModelId: String?
        get() = preferences.getString("active_model_id", null) ?: importedModel?.let { IMPORTED_MODEL_ID }
    val importedModelName: String get() = preferences.getString("imported_name", "Model GGUF impor") ?: "Model GGUF impor"
    val selectedModelName: String?
        get() = when (val id = selectedModelId) {
            IMPORTED_MODEL_ID -> importedModelName
            else -> ModelCatalog.get(id.orEmpty())?.takeIf { it.kind == ModelKind.AI_TRANSLATION }?.label
        }
    val modelFile: File?
        get() = offlineModelCandidate(selectedModelId, installedFile, localModelDirectory(context))

    suspend fun selectModel(id: String) = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        runInterruptible {
            OfflineAiAccess.lock.lockInterruptibly()
            try {
                val file = requireNotNull(offlineModelCandidate(id, installedFile, localModelDirectory(context))) {
                    "Unduh model sampai selesai sebelum mengaktifkannya"
                }
                verifyCatalogModel(id, file)
                coroutine.ensureActive()
                check(preferences.edit().putString("active_model_id", id).commit()) { "Pilihan model tidak dapat disimpan" }
            } finally { OfflineAiAccess.lock.unlock() }
        }
    }

    suspend fun verifiedModelFile(): File? = withContext(Dispatchers.IO) {
        runInterruptible {
            OfflineAiAccess.lock.lockInterruptibly()
            try {
                val id = selectedModelId ?: return@runInterruptible null
                val file = modelFile ?: return@runInterruptible null
                verifyCatalogModel(id, file)
                file
            } finally { OfflineAiAccess.lock.unlock() }
        }
    }

    suspend fun removeImportedModel() = withContext(Dispatchers.IO) {
        runInterruptible {
            OfflineAiAccess.lock.lockInterruptibly()
            try {
                check(!installedFile.exists() || installedFile.delete()) { "Model impor tidak dapat dihapus" }
                if (preferences.getString("active_model_id", null) == IMPORTED_MODEL_ID) {
                    preferences.edit().remove("active_model_id").apply()
                }
                preferences.edit().remove("imported_name").apply()
            } finally { OfflineAiAccess.lock.unlock() }
        }
    }

    private fun verifyCatalogModel(id: String, file: File) {
        if (id == IMPORTED_MODEL_ID) return
        val spec = requireNotNull(ModelCatalog.get(id)?.takeIf { it.kind == ModelKind.AI_TRANSLATION }) { "Model AI tidak dikenal" }
        check(VerifiedModelInstaller.matches(file, spec.bytes, spec.sha256)) { "Verifikasi model AI gagal; hapus dan unduh ulang model" }
    }

    suspend fun importModel(uri: Uri, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        runInterruptible {
            OfflineAiAccess.lock.lockInterruptibly()
            try {
                coroutine.ensureActive()
                check(directory.isDirectory || directory.mkdirs()) { "Folder model AI tidak dapat dibuat" }
                var declaredSize = -1L
                var displayName: String? = null
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                    }
                }
                require(displayName == null || displayName!!.endsWith(".gguf", ignoreCase = true)) {
                    "Pilih berkas model instruct/chat .gguf"
                }
                require(declaredSize <= MAX_OFFLINE_MODEL_BYTES) { "Model AI maksimal 4 GiB" }
                require(declaredSize < 0 || declaredSize >= MIN_OFFLINE_MODEL_BYTES) { "Berkas model AI terlalu kecil" }
                check(directory.usableSpace > DISK_RESERVE_BYTES + declaredSize.coerceAtLeast(0)) {
                    "Penyimpanan tidak cukup untuk mengimpor model AI"
                }
                val temporary = File.createTempFile("import-", ".part", directory)
                try {
                    onProgress(0)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var copied = 0L
                            var previousProgress = 0
                            while (true) {
                                coroutine.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                copied += count
                                require(copied <= MAX_OFFLINE_MODEL_BYTES) { "Model AI maksimal 4 GiB" }
                                check(directory.usableSpace > DISK_RESERVE_BYTES + count) {
                                    "Penyimpanan habis saat mengimpor model AI"
                                }
                                output.write(buffer, 0, count)
                                val progress = if (declaredSize > 0) (copied * 99 / declaredSize).toInt().coerceIn(0, 99) else 0
                                if (progress != previousProgress) {
                                    previousProgress = progress
                                    onProgress(progress)
                                }
                            }
                            require(declaredSize < 0 || copied == declaredSize) { "Berkas model GGUF tidak lengkap" }
                            output.fd.sync()
                        }
                    } ?: error("Berkas model AI tidak dapat dibuka")
                    coroutine.ensureActive()
                    validateOfflineGguf(temporary)
                    // POSIX rename on the same private filesystem atomically replaces the old file.
                    // Until this point, every failure or cancellation leaves the previous model intact.
                    Os.rename(temporary.absolutePath, installedFile.absolutePath)
                    preferences.edit().putString("active_model_id", IMPORTED_MODEL_ID)
                        .putString("imported_name", displayName ?: "Model GGUF impor").apply()
                    runCatching { onProgress(100) }
                    installedFile
                } finally {
                    temporary.delete()
                }
            } finally {
                OfflineAiAccess.lock.unlock()
            }
        }
    }
}
