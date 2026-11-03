package id.tomz.transcriptor.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import id.tomz.transcriptor.data.ModelDownloadEntity
import id.tomz.transcriptor.data.TranscriptDao
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class PlatformDownloadState { QUEUED, RUNNING, PAUSED }

internal fun platformDownloadState(status: Int): PlatformDownloadState? = when (status) {
    DownloadManager.STATUS_PENDING -> PlatformDownloadState.QUEUED
    DownloadManager.STATUS_RUNNING -> PlatformDownloadState.RUNNING
    DownloadManager.STATUS_PAUSED -> PlatformDownloadState.PAUSED
    else -> null
}

internal fun modelDownloadSources(spec: ModelSpec): List<String> =
    listOfNotNull(spec.mirrorUrl, spec.upstreamUrl).distinct()

internal fun shouldTryNextDownloadSource(reason: Int): Boolean = when (reason) {
    DownloadManager.ERROR_FILE_ERROR,
    DownloadManager.ERROR_INSUFFICIENT_SPACE,
    DownloadManager.ERROR_DEVICE_NOT_FOUND,
    DownloadManager.ERROR_FILE_ALREADY_EXISTS,
    -> false
    else -> true
}

internal data class ModelLibraryEntryState(
    val spec: ModelSpec,
    val installed: Boolean = false,
)

internal data class ModelLibraryState(
    val entries: List<ModelLibraryEntryState> = ModelCatalog.all.map { ModelLibraryEntryState(it) },
    val isRefreshing: Boolean = true,
    val busyModelId: String? = null,
    val isDownloading: Boolean = false,
    val isVerifying: Boolean = false,
    val downloadStatus: PlatformDownloadState? = null,
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val errorModelId: String? = null,
    val errorMessage: String? = null,
)

internal fun ModelLibraryState.reconcileInstalledModel(
    modelId: String,
    isVerified: Boolean,
): ModelLibraryState = copy(
    entries = entries.map { entry ->
        if (entry.spec.id == modelId) entry.copy(installed = isVerified) else entry
    },
)

internal fun verifiedInstalledModels(directory: File, specs: List<ModelSpec>): Map<String, Boolean> =
    specs.associate { spec ->
        spec.id to VerifiedModelInstaller.matches(File(directory, spec.fileName), spec.bytes, spec.sha256)
    }

@Singleton
class ModelLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TranscriptDao,
) {
    private val appContext = context.applicationContext
    private val modelDirectory = localModelDirectory(appContext)
    private val legacyModelDirectory = File(appContext.filesDir, "models")
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
        ?: throw IllegalStateException("Download manager tidak tersedia")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var operationJob: Job? = null
    private val mutableState = MutableStateFlow(ModelLibraryState())

    internal val state = mutableState.asStateFlow()

    init {
        val job = scope.launch(start = CoroutineStart.LAZY) { initialize() }
        synchronized(lock) { operationJob = job }
        job.start()
    }

    fun download(modelId: String) {
        val spec = ModelCatalog.get(modelId) ?: return
        synchronized(lock) {
            if (mutableState.value.isRefreshing || mutableState.value.busyModelId != null) return
            mutableState.update {
                it.copy(
                    busyModelId = spec.id,
                    isDownloading = true,
                    isVerifying = false,
                    downloadStatus = PlatformDownloadState.QUEUED,
                    downloadedBytes = 0,
                    downloadTotalBytes = spec.downloadBytes,
                    errorModelId = null,
                    errorMessage = null,
                )
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { performDownload(spec) }
            operationJob = job
            job.start()
        }
    }

    fun cancelDownload() {
        synchronized(lock) {
            if (mutableState.value.isDownloading) operationJob?.cancel()
        }
    }

    fun remove(modelId: String) {
        val spec = ModelCatalog.get(modelId) ?: return
        synchronized(lock) {
            if (mutableState.value.isRefreshing || mutableState.value.busyModelId != null) return
            mutableState.update {
                it.copy(
                    busyModelId = spec.id,
                    isDownloading = false,
                    isVerifying = false,
                    downloadStatus = null,
                    errorModelId = null,
                    errorMessage = null,
                )
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    listOf(File(modelDirectory, spec.fileName), File(legacyModelDirectory, spec.fileName))
                        .distinctBy(File::getAbsolutePath)
                        .forEach { file ->
                            if (file.exists() && !file.delete()) throw IOException("Tidak dapat menghapus ${spec.label}")
                        }
                    updateEntry(spec.id) { it.copy(installed = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update {
                        it.copy(errorModelId = spec.id, errorMessage = error.message ?: "Tidak dapat menghapus model")
                    }
                } finally {
                    clearBusyState(spec.id)
                }
            }
            operationJob = job
            job.start()
        }
    }

    private suspend fun initialize() {
        try {
            migrateLegacyModels()
            val installed = verifiedInstalledModels(modelDirectory, ModelCatalog.all)
            val records = dao.modelDownloads()
            mutableState.update { current ->
                current.copy(
                    entries = current.entries.map { entry -> entry.copy(installed = installed[entry.spec.id] == true) },
                )
            }
            for (record in records) {
                val spec = ModelCatalog.get(record.modelId)
                if (spec == null) {
                    discardDownload(record.modelId, record.downloadId)
                    continue
                }
                mutableState.update {
                    it.copy(
                        busyModelId = spec.id,
                        isDownloading = true,
                        isVerifying = false,
                        downloadStatus = PlatformDownloadState.QUEUED,
                        downloadedBytes = 0,
                        downloadTotalBytes = spec.downloadBytes,
                    )
                }
                try {
                    attemptDownloadSources(spec, record.sourceIndex, record)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update {
                        it.copy(errorModelId = spec.id, errorMessage = error.message ?: "Unduhan model gagal")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    errorModelId = it.errorModelId ?: ModelCatalog.whisperModels.firstOrNull()?.id,
                    errorMessage = error.message ?: "Tidak dapat memulihkan unduhan model",
                )
            }
        } finally {
            // Initialization owns the operation through all recovered records.
            // Publish idle state and release ownership together, so a new download
            // cannot be overwritten by this job's cleanup.
            synchronized(lock) {
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        busyModelId = null,
                        isDownloading = false,
                        isVerifying = false,
                        downloadStatus = null,
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                    )
                }
                operationJob = null
            }
        }
    }

    private suspend fun performDownload(spec: ModelSpec) {
        val partial = File(modelDirectory, "${spec.id}.download.part")
        try {
            if (VerifiedModelInstaller.matches(File(modelDirectory, spec.fileName), spec.bytes, spec.sha256)) {
                updateEntry(spec.id) { it.copy(installed = true) }
                return
            }
            val destinationDirectory = appContext.getExternalFilesDir("models")
                ?: throw IOException("Penyimpanan aplikasi tidak tersedia untuk unduhan model")
            if (!destinationDirectory.isDirectory && !destinationDirectory.mkdirs()) {
                throw IOException("Tidak dapat menyiapkan penyimpanan model")
            }
            if (destinationDirectory.usableSpace < spec.downloadBytes + 128L * 1024 * 1024) {
                throw IOException("Penyimpanan tidak cukup untuk mengunduh ${spec.label}")
            }
            attemptDownloadSources(spec, startIndex = 0)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
            mutableState.update {
                it.copy(errorModelId = spec.id, errorMessage = error.message ?: "Unduhan model gagal")
            }
        } finally {
            clearBusyState(spec.id)
        }
    }

    private suspend fun attemptDownloadSources(
        spec: ModelSpec,
        startIndex: Int,
        resumed: ModelDownloadEntity? = null,
    ) {
        val sources = modelDownloadSources(spec)
        if (sources.isEmpty()) throw IOException("Tidak ada sumber unduhan untuk ${spec.label}")
        val firstIndex = startIndex.coerceIn(sources.indices)
        val partial = File(modelDirectory, "${spec.id}.download.part")
        for (sourceIndex in firstIndex..sources.lastIndex) {
            var downloadId: Long? = null
            try {
                val isResuming = resumed != null && sourceIndex == firstIndex
                if (!isResuming && partial.exists() && !partial.delete()) {
                    throw IOException("Tidak dapat membersihkan unduhan model sebelumnya")
                }
                downloadId = if (isResuming) {
                    requireNotNull(resumed).downloadId
                } else {
                    val request = DownloadManager.Request(Uri.parse(sources[sourceIndex]))
                        .setTitle(spec.label)
                        .setDescription(spec.licence)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(false)
                        .setDestinationInExternalFilesDir(appContext, "models", partial.name)
                    downloadManager.enqueue(request)
                }
                if (downloadId <= 0L) throw IOException("Download manager menolak unduhan model")
                if (resumed == null || sourceIndex != firstIndex) {
                    dao.putModelDownload(ModelDownloadEntity(spec.id, downloadId, sourceIndex))
                }
                mutableState.update { current ->
                    if (current.busyModelId == spec.id) {
                        current.copy(
                            isDownloading = true,
                    isVerifying = false,
                            downloadStatus = PlatformDownloadState.QUEUED,
                            downloadedBytes = 0,
                            downloadTotalBytes = spec.downloadBytes,
                        )
                    } else {
                        current
                    }
                }
                monitorAndInstall(spec, downloadId)
                updateEntry(spec.id) { it.copy(installed = true) }
                return
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    downloadId?.let { discardDownload(spec.id, it) }
                    partial.delete()
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    downloadId?.let { discardDownload(spec.id, it) }
                    partial.delete()
                }
                if (
                    sourceIndex == sources.lastIndex ||
                    error !is DownloadSourceFailure ||
                    !shouldTryNextDownloadSource(error.reason)
                ) {
                    throw error
                }
                mutableState.update { current ->
                    if (current.busyModelId == spec.id) {
                        current.copy(
                            downloadStatus = PlatformDownloadState.QUEUED,
                            downloadedBytes = 0,
                            downloadTotalBytes = spec.downloadBytes,
                            errorModelId = null,
                            errorMessage = null,
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    private suspend fun monitorAndInstall(spec: ModelSpec, downloadId: Long) {
        try {
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val snapshot = queryDownload(downloadId)
                    ?: throw DownloadSourceFailure(spec.label, DownloadManager.ERROR_UNKNOWN)
                val state = platformDownloadState(snapshot.status)
                if (state != null) {
                    mutableState.update { current ->
                        if (current.busyModelId == spec.id) {
                            current.copy(
                                isDownloading = true,
                    isVerifying = false,
                                downloadStatus = state,
                                downloadedBytes = snapshot.bytesDownloaded.coerceAtLeast(0L),
                                downloadTotalBytes = snapshot.totalBytes.takeIf { it > 0L } ?: spec.downloadBytes,
                            )
                        } else {
                            current
                        }
                    }
                    delay(DOWNLOAD_POLL_INTERVAL_MS)
                    continue
                }

                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        mutableState.update { it.copy(isDownloading = false, isVerifying = true) }
                        installDownloadedModel(spec)
                        discardDownload(spec.id, downloadId)
                        return
                    }
                    DownloadManager.STATUS_FAILED -> throw DownloadSourceFailure(spec.label, snapshot.reason)
                    else -> throw IOException("Status unduhan ${spec.label} tidak dikenal")
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { discardDownload(spec.id, downloadId) }
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { discardDownload(spec.id, downloadId) }
            throw error
        }
    }

    private fun queryDownload(downloadId: Long): DownloadSnapshot? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadSnapshot(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private fun installDownloadedModel(spec: ModelSpec) {
        val destination = File(modelDirectory, spec.fileName)
        if (VerifiedModelInstaller.matches(destination, spec.bytes, spec.sha256)) return
        val partial = File(modelDirectory, "${spec.id}.download.part")
        val archiveMember = spec.archiveMember
        if (archiveMember == null) {
            if (spec.downloadBytes != spec.bytes || !spec.downloadSha256.equals(spec.sha256, ignoreCase = true)) {
                throw IOException("Metadata unduhan model ${spec.label} tidak konsisten")
            }
            VerifiedModelInstaller.install(partial, destination, spec)
            return
        }

        VerifiedModelInstaller.verify(partial, spec.downloadBytes, spec.downloadSha256)
        val extracted = File(modelDirectory, "${spec.id}.extract.part")
        try {
            SecureModelArchive.extractVerifiedEntry(partial, archiveMember, extracted, spec.bytes)
            VerifiedModelInstaller.install(extracted, destination, spec)
        } finally {
            extracted.delete()
            partial.delete()
        }
    }

    private suspend fun discardDownload(modelId: String, downloadId: Long) {
        downloadManager.remove(downloadId)
        dao.deleteModelDownload(modelId)
    }

    private fun migrateLegacyModels() {
        if (legacyModelDirectory.absoluteFile == modelDirectory.absoluteFile) return
        if (!modelDirectory.isDirectory && !modelDirectory.mkdirs()) throw IOException("Tidak dapat menyiapkan penyimpanan model")
        for (spec in ModelCatalog.all) {
            val destination = File(modelDirectory, spec.fileName)
            val legacy = File(legacyModelDirectory, spec.fileName)
            if (VerifiedModelInstaller.matches(destination, spec.bytes, spec.sha256) ||
                !VerifiedModelInstaller.matches(legacy, spec.bytes, spec.sha256)
            ) {
                continue
            }
            val partial = File(modelDirectory, "${spec.id}.migration.part")
            try {
                legacy.copyTo(partial, overwrite = true)
                VerifiedModelInstaller.install(partial, destination, spec)
                legacy.delete()
            } finally {
                partial.delete()
            }
        }
    }

    private fun clearBusyState(modelId: String) {
        synchronized(lock) {
            if (mutableState.value.busyModelId == modelId) {
                mutableState.update {
                    it.copy(
                        busyModelId = null,
                        isDownloading = false,
                    isVerifying = false,
                        downloadStatus = null,
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                    )
                }
            }
            operationJob = null
        }
    }

    private fun updateEntry(modelId: String, transform: (ModelLibraryEntryState) -> ModelLibraryEntryState) {
        mutableState.update { current ->
            current.copy(entries = current.entries.map { entry ->
                if (entry.spec.id == modelId) transform(entry) else entry
            })
        }
    }

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )
    private class DownloadSourceFailure(label: String, val reason: Int) :
        IOException("Unduhan $label gagal (kode $reason)")

    private companion object {
        const val DOWNLOAD_POLL_INTERVAL_MS = 500L
    }
}
