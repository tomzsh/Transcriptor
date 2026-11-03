package id.tomz.transcriptor.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.isProcessing
import id.tomz.transcriptor.domain.TranscriptionLanguages
import id.tomz.transcriptor.domain.SegmentEditError
import id.tomz.transcriptor.domain.TranscriptDocument
import id.tomz.transcriptor.domain.TranscriptProject
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranscriptSpeaker
import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ProjectDeletionResult { DELETED, ACTIVE, NOT_FOUND, STORAGE_FAILURE, CLEANUP_PENDING }

@Singleton
class MediaProjectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TranscriptDao,
) {
    private val startupMutex = Mutex()
    private var startupPrepared = false
    private val legacyMigrationMutex = Mutex()
    private var legacyMigrationFinished = false
    fun observeProjects(): Flow<List<TranscriptProject>> = dao.observeProjects().map { projects ->
        projects.map { it.toDomain() }
    }

    fun observeTranscript(projectId: String): Flow<TranscriptDocument?> = dao.observeProject(projectId).map { aggregate ->
        aggregate?.let {
            TranscriptDocument(
                project = it.project.toDomain(),
                segments = it.segments.sortedBy { segment -> segment.ordinal }.map { segment -> segment.toDomain() },
                speakers = it.speakers.sortedBy { speaker -> speaker.clusterId ?: Int.MAX_VALUE }
                    .map { speaker -> speaker.toDomain() },
            )
        }
    }

    suspend fun updateSegment(segment: TranscriptSegment): SegmentEditError? = withContext(Dispatchers.IO) {
        dao.updateSegmentChecked(
            SegmentEntity(
                id = segment.id,
                projectId = segment.projectId,
                ordinal = segment.ordinal,
                startMs = segment.startMs,
                endMs = segment.endMs,
                text = segment.text,
                speakerId = segment.speakerId,
            ),
        )
    }

    suspend fun createSpeaker(projectId: String, name: String): TranscriptSpeaker? = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_SPEAKER_NAME_LENGTH) {
            return@withContext null
        }
        val speaker = SpeakerEntity(
            id = "$projectId:manual:${UUID.randomUUID()}",
            projectId = projectId,
            name = normalizedName,
            clusterId = null,
        )
        if (dao.insertEditableSpeaker(speaker)) speaker.toDomain() else null
    }

    suspend fun renameSpeaker(speakerId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_SPEAKER_NAME_LENGTH) return@withContext false
        dao.renameEditableSpeaker(speakerId, normalizedName)
    }

    fun mediaUri(mediaPath: String): Uri {
        val uri = Uri.parse(mediaPath)
        return if (uri.scheme == "content" || uri.scheme == "file") uri else Uri.fromFile(File(mediaPath))
    }

    suspend fun deleteProject(projectId: String): ProjectDeletionResult = withContext(Dispatchers.IO) {
        val project = dao.project(projectId) ?: return@withContext ProjectDeletionResult.NOT_FOUND
        if (project.state.isProcessing() || project.state == ProjectState.DELETING) {
            return@withContext ProjectDeletionResult.ACTIVE
        }
        val previous = dao.claimProjectForDeletion(projectId)
            ?: return@withContext ProjectDeletionResult.ACTIVE

        val directory = projectDirectory(projectId)
        val tombstone = File(projectRoot(), "$projectId.deleting")
        if (tombstone.exists() && !tombstone.deleteRecursively()) {
            dao.updateProject(previous)
            return@withContext ProjectDeletionResult.STORAGE_FAILURE
        }
        if (directory.exists() && !directory.renameTo(tombstone)) {
            dao.updateProject(previous)
            return@withContext ProjectDeletionResult.STORAGE_FAILURE
        }

        val removed = try {
            dao.deleteClaimedProject(projectId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (tombstone.exists()) tombstone.renameTo(directory)
                dao.updateProject(previous)
            }
            throw cancelled
        } catch (error: Exception) {
            if (tombstone.exists()) tombstone.renameTo(directory)
            dao.updateProject(previous)
            throw error
        }
        if (removed == 0) {
            if (tombstone.exists()) tombstone.renameTo(directory)
            dao.updateProject(previous)
            return@withContext if (dao.project(projectId) == null) {
                ProjectDeletionResult.NOT_FOUND
            } else {
                ProjectDeletionResult.ACTIVE
            }
        }
        if (!tombstone.exists() || tombstone.deleteRecursively()) {
            ProjectDeletionResult.DELETED
        } else {
            ProjectDeletionResult.CLEANUP_PENDING
        }
    }

    suspend fun prepareForStartup() = withContext(Dispatchers.IO) {
        startupMutex.withLock {
            if (startupPrepared) return@withLock
            dao.completeAbandonedProjectDeletions()
            dao.recoverAbandonedProjects()
            val projectIds = dao.observeProjects().first().map(ProjectEntity::id).toSet()
            projectRoot().listFiles().orEmpty().forEach { directory ->
                if (directory.name.endsWith(".deleting") || directory.name !in projectIds) {
                    directory.deleteRecursively()
                }
            }
            startupPrepared = true
        }
    }

    suspend fun migrateLegacyMediaSources() = withContext(Dispatchers.IO) {
        legacyMigrationMutex.withLock {
            if (legacyMigrationFinished) return@withLock
            dao.observeProjects().first().forEach { project ->
                val uri = Uri.parse(project.mediaPath)
                if (uri.scheme != "content" && uri.scheme != "file") return@forEach
                val directory = projectDirectory(project.id)
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Berkas media tidak dapat dibuka")
                    val name = displayName(uri)
                    val source = input.use { copyMediaSourceToProject(it, directory, name) }
                    if (dao.updateProjectMediaPath(project.id, project.mediaPath, source.absolutePath) == 0) {
                        directory.deleteRecursively()
                    }
                } catch (cancelled: CancellationException) {
                    directory.deleteRecursively()
                    throw cancelled
                } catch (_: Exception) {
                    directory.deleteRecursively()
                }
            }
            legacyMigrationFinished = true
        }
    }


    suspend fun import(
        uri: Uri,
        modelId: String,
        language: String = "auto",
        autoSpeakers: Boolean = false,
    ): TranscriptProject = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri)
        val declaredMime = resolver.getType(uri)?.lowercase()
        val mimeType = declaredMime?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
            ?: guessMimeType(name)
        if (!mimeType.startsWith("audio/") && !mimeType.startsWith("video/")) {
            throw IOException("Pilih berkas audio atau video yang didukung")
        }
        val model = ModelCatalog.get(modelId)?.takeIf { it.kind == ModelKind.WHISPER }
            ?: throw IOException("Pilih model Whisper sebelum mengimpor")

        val projectId = UUID.randomUUID().toString()
        val projectDirectory = projectDirectory(projectId)
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Berkas media tidak dapat dibuka")
            val source = input.use { copyMediaSourceToProject(it, projectDirectory, name) }
            val durationMs = readDuration(Uri.fromFile(source))
            if (durationMs <= 0L) throw IOException("Durasi media tidak dapat dibaca")

            val project = ProjectEntity(
                id = projectId,
                title = name.substringBeforeLast('.', name).takeIf(String::isNotBlank) ?: "Transkrip baru",
                mediaPath = source.absolutePath,
                mediaMimeType = mimeType,
                durationMs = durationMs,
                modelId = modelId,
                language = TranscriptionLanguages.forModel(language, model.englishOnly, model.transcriptionLanguage),
                autoSpeakers = autoSpeakers,
                state = ProjectState.IMPORTED,
                createdAt = System.currentTimeMillis(),
            )
            dao.insertProject(project)
            project.toDomain()
        } catch (error: Exception) {
            projectDirectory.deleteRecursively()
            throw error
        }
    }


    fun normalizedAudioFile(projectId: String): File = File(projectDirectory(projectId), "normalized.f32le")

    private fun projectRoot(): File = File(context.filesDir, "projects")

    private fun projectDirectory(projectId: String): File = File(projectRoot(), projectId)


    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("Durasi media tidak dapat dibaca")
        } catch (error: RuntimeException) {
            throw IOException("Berkas media tidak dapat dibuka", error)
        } finally {
            retriever.release()
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Transkrip baru"
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}
 
internal fun copyMediaSourceToProject(input: InputStream, projectDirectory: File, displayName: String): File {
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .filter(Char::isLetterOrDigit)
        .take(10)
        .ifEmpty { "media" }
    val destination = File(projectDirectory, "source.$extension")
    try {
        if (!projectDirectory.isDirectory && !projectDirectory.mkdirs()) {
            throw IOException("Could not create project media directory")
        }
        FileOutputStream(destination).use { output ->
            input.copyTo(output, DEFAULT_MEDIA_COPY_BUFFER_BYTES)
            output.fd.sync()
        }
        if (destination.length() == 0L) throw IOException("Berkas media kosong")
        return destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

private const val DEFAULT_MEDIA_COPY_BUFFER_BYTES = 64 * 1024

private const val MAX_SPEAKER_NAME_LENGTH = 48

