package id.tomz.transcriptor.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.SegmentEditError
import id.tomz.transcriptor.domain.TranscriptProject
import id.tomz.transcriptor.domain.TranscriptSegment
import id.tomz.transcriptor.domain.TranscriptSpeaker
import id.tomz.transcriptor.domain.validateSegmentEdit
import id.tomz.transcriptor.domain.isProcessing
import id.tomz.transcriptor.speaker.SpeakerTurn
import id.tomz.transcriptor.speaker.assignSpeakers
import id.tomz.transcriptor.speaker.orderedSpeakerClusterIds
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects", indices = [Index("createdAt")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mediaPath: String,
    val mediaMimeType: String,
    val durationMs: Long,
    val modelId: String,
    val language: String,
    val autoSpeakers: Boolean,
    val state: ProjectState,
    val progress: Int = 0,
    val error: String? = null,
    val speakerWarning: String? = null,
    val createdAt: Long,
    val translationLanguage: String? = null,
)

@Entity(tableName = "model_downloads")
data class ModelDownloadEntity(
    @PrimaryKey val modelId: String,
    val downloadId: Long,
    @ColumnInfo(defaultValue = "0") val sourceIndex: Int = 0,
)

@Entity(
    tableName = "speakers",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index(value = ["projectId", "clusterId"], unique = true)],
)
data class SpeakerEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val clusterId: Int?,
)

@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SpeakerEntity::class, parentColumns = ["id"], childColumns = ["speakerId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("projectId"), Index("speakerId"), Index(value = ["projectId", "ordinal"], unique = true)],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String?,
    @ColumnInfo(defaultValue = "0") val isManuallyEdited: Boolean = false,
    @ColumnInfo(defaultValue = "0") val speakerAssignmentEdited: Boolean = false,
    val translatedText: String? = null,
)

class ProjectStateConverter {
    @TypeConverter fun toDatabase(value: ProjectState): String = value.name
    @TypeConverter fun fromDatabase(value: String): ProjectState = ProjectState.valueOf(value)
}

data class ProjectAggregate(
    @Embedded val project: ProjectEntity,
    @Relation(parentColumn = "id", entityColumn = "projectId") val segments: List<SegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "projectId") val speakers: List<SpeakerEntity>,
)

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun project(projectId: String): ProjectEntity?
    @Query("UPDATE projects SET mediaPath = :newPath WHERE id = :projectId AND mediaPath = :oldPath")
    suspend fun updateProjectMediaPath(projectId: String, oldPath: String, newPath: String): Int

    @Query("UPDATE projects SET progress = :progress WHERE id = :projectId AND state = :state AND progress < :progress")
    suspend fun updateProgress(projectId: String, state: ProjectState, progress: Int): Int

    @Transaction
    suspend fun claimProjectForTranslation(projectId: String): ProjectEntity? {
        val current = project(projectId) ?: return null
        if (current.state.isProcessing() || current.state == ProjectState.DELETING || segments(projectId).isEmpty()) return null
        val claimed = current.copy(state = ProjectState.DOWNLOADING_TRANSLATION, progress = 0, error = null)
        updateProject(claimed)
        return claimed
    }

    @Transaction
    suspend fun saveTranslation(projectId: String, target: String, originals: List<SegmentEntity>, texts: List<String>) {
        val current = project(projectId) ?: error("Proyek tidak lagi tersedia")
        check(current.state == ProjectState.TRANSLATING) { "Terjemahan tidak lagi aktif" }
        val saved = segments(projectId)
        check(saved.map { it.id to it.text } == originals.map { it.id to it.text }) { "Teks berubah; ulangi terjemahan" }
        check(texts.size == saved.size && texts.all { it.isNotBlank() }) { "Hasil terjemahan tidak lengkap" }
        putSegments(saved.mapIndexed { index, segment -> segment.copy(translatedText = texts[index]) })
        updateProject(current.copy(state = ProjectState.COMPLETED, progress = 100, translationLanguage = target, error = null))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putModelDownload(download: ModelDownloadEntity)

    @Query("SELECT * FROM model_downloads WHERE modelId = :modelId")
    suspend fun modelDownload(modelId: String): ModelDownloadEntity?

    @Query("SELECT * FROM model_downloads ORDER BY modelId")
    suspend fun modelDownloads(): List<ModelDownloadEntity>

    @Query("DELETE FROM model_downloads WHERE modelId = :modelId")
    suspend fun deleteModelDownload(modelId: String)

    @Transaction
    suspend fun claimProjectForProcessing(projectId: String): ProjectEntity? {
        val current = project(projectId) ?: return null
        if (current.state.isProcessing() || current.state == ProjectState.DELETING) {
            return null
        }
        val claimed = current.copy(
            state = ProjectState.DECODING,
            progress = 0,
            error = null,
            speakerWarning = null,
        )
        updateProject(claimed)
        return claimed
    }

    @Transaction
    suspend fun claimProjectForDeletion(projectId: String): ProjectEntity? {
        val current = project(projectId) ?: return null
        if (current.state.isProcessing() || current.state == ProjectState.DELETING) {
            return null
        }
        updateProject(current.copy(state = ProjectState.DELETING))
        return current
    }

    @Query("DELETE FROM projects WHERE state = 'DELETING'")
    suspend fun completeAbandonedProjectDeletions()

    @Query("DELETE FROM projects WHERE id = :projectId AND state = 'DELETING'")
    suspend fun deleteClaimedProject(projectId: String): Int

    @Query(
        "UPDATE projects SET state = 'INTERRUPTED', progress = 0, error = NULL " +
            "WHERE state IN ('DECODING', 'TRANSCRIBING', 'DIARIZING', 'DOWNLOADING_TRANSLATION', 'TRANSLATING')",
    )
    suspend fun recoverAbandonedProjects()

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :projectId")
    fun observeProject(projectId: String): Flow<ProjectAggregate?>

    @Transaction
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAllProjects(): Flow<List<ProjectAggregate>>

    @Query("SELECT * FROM segments WHERE projectId = :projectId ORDER BY ordinal")
    suspend fun segments(projectId: String): List<SegmentEntity>

    @Update
    suspend fun updateSegment(segment: SegmentEntity)

    @Transaction
    suspend fun updateSegmentChecked(segment: SegmentEntity): SegmentEditError? {
        val project = project(segment.projectId) ?: return SegmentEditError.PROJECT_NOT_FOUND
        if (project.state.isProcessing() || project.state == ProjectState.DELETING) return SegmentEditError.PROJECT_BUSY
        val currentSegments = segments(segment.projectId)
        val index = currentSegments.indexOfFirst { it.id == segment.id }
        if (index < 0) return SegmentEditError.SEGMENT_NOT_FOUND
        val current = currentSegments[index]
        val error = validateSegmentEdit(
            segment = segment.toDomain(),
            previous = currentSegments.getOrNull(index - 1)?.toDomain(),
            next = currentSegments.getOrNull(index + 1)?.toDomain(),
            durationMs = project.durationMs,
            speakerIds = speakers(segment.projectId).map { it.id }.toSet(),
        )
        if (error != null) return error
        updateSegment(
            segment.copy(
                isManuallyEdited = true,
                speakerAssignmentEdited = current.speakerAssignmentEdited || segment.speakerId != current.speakerId,
                translatedText = current.translatedText.takeIf { current.text == segment.text },
            ),
        )
        return null
    }

    @Query("SELECT * FROM speakers WHERE projectId = :projectId ORDER BY rowid")
    suspend fun speakers(projectId: String): List<SpeakerEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpeaker(speaker: SpeakerEntity)

    @Query("SELECT * FROM speakers WHERE id = :speakerId")
    suspend fun speaker(speakerId: String): SpeakerEntity?

    @Update
    suspend fun updateSpeaker(speaker: SpeakerEntity)

    @Transaction
    suspend fun insertEditableSpeaker(speaker: SpeakerEntity): Boolean {
        val current = project(speaker.projectId) ?: return false
        if (current.state.isProcessing() || current.state == ProjectState.DELETING) return false
        insertSpeaker(speaker)
        return true
    }

    @Transaction
    suspend fun renameEditableSpeaker(speakerId: String, name: String): Boolean {
        val speaker = speaker(speakerId) ?: return false
        val current = project(speaker.projectId) ?: return false
        if (current.state.isProcessing() || current.state == ProjectState.DELETING) return false
        updateSpeaker(speaker.copy(name = name))
        return true
    }

    @Upsert
    suspend fun putSegments(segments: List<SegmentEntity>)

    @Upsert
    suspend fun putSpeakers(speakers: List<SpeakerEntity>)

    @Query("DELETE FROM segments WHERE projectId = :projectId")
    suspend fun deleteSegments(projectId: String)

    @Query("DELETE FROM speakers WHERE projectId = :projectId")
    suspend fun deleteSpeakers(projectId: String)

    @Transaction
    suspend fun applyDiarization(
        projectId: String,
        detectedSpeakers: List<SpeakerEntity>,
        turns: List<SpeakerTurn>,
    ) {
        val currentSegments = segments(projectId)
        if (currentSegments.isEmpty()) return

        val orderedClusterIds = orderedSpeakerClusterIds(turns)
        if (orderedClusterIds.isEmpty()) return
        val incomingByCluster = detectedSpeakers.mapNotNull { speaker ->
            speaker.clusterId?.let { it to speaker }
        }.toMap()
        val existingSpeakers = speakers(projectId)
        val existingById = existingSpeakers.associateBy { it.id }
        val diarizedSpeakers = orderedClusterIds.mapIndexedNotNull { index, clusterId ->
            incomingByCluster[clusterId]?.let { detected ->
                val id = "$projectId:speaker-${index + 1}"
                detected.copy(
                    id = id,
                    projectId = projectId,
                    name = existingById[id]?.name ?: detected.name,
                )
            }
        }
        val diarizedIds = diarizedSpeakers.mapTo(mutableSetOf(), SpeakerEntity::id)
        val manuallyAssignedIds = currentSegments
            .filter { it.speakerAssignmentEdited }
            .mapNotNullTo(mutableSetOf(), SegmentEntity::speakerId)
        val retainedSpeakers = existingSpeakers.filter {
            it.clusterId == null || (it.id in manuallyAssignedIds && it.id !in diarizedIds)
        }
        val assignedSpeakerIds = assignSpeakers(
            projectId,
            currentSegments.map(SegmentEntity::toDomain),
            turns,
        ).associate { it.id to it.speakerId }

        deleteSpeakers(projectId)
        putSpeakers(retainedSpeakers + diarizedSpeakers)
        for (segment in currentSegments) {
            updateSegment(
                segment.copy(
                    speakerId = if (segment.speakerAssignmentEdited) {
                        segment.speakerId
                    } else {
                        assignedSpeakerIds[segment.id]
                    },
                ),
            )
        }
    }

    @Transaction
    suspend fun replaceTranscript(projectId: String, speakers: List<SpeakerEntity>, segments: List<SegmentEntity>) {
        val existingSegments = this.segments(projectId)
        val preservedEdits = existingSegments.filter(SegmentEntity::isManuallyEdited)
        val regenerated = segments.filter { incoming ->
            preservedEdits.none { edited ->
                incoming.startMs < edited.endMs && edited.startMs < incoming.endMs
            }
        }
        val mergedSegments = (regenerated + preservedEdits)
            .sortedWith(compareBy<SegmentEntity> { it.startMs }.thenBy { it.endMs }.thenBy { it.ordinal })
            .mapIndexed { index, segment -> segment.copy(ordinal = index) }

        val existingSpeakers = this.speakers(projectId).associateBy(SpeakerEntity::id)
        val speakersWithSavedNames = speakers.map { incoming ->
            existingSpeakers[incoming.id]?.let { incoming.copy(name = it.name) } ?: incoming
        }

        deleteSegments(projectId)
        if (speakersWithSavedNames.isNotEmpty()) putSpeakers(speakersWithSavedNames)
        if (mergedSegments.isNotEmpty()) putSegments(mergedSegments)
    }
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `model_downloads` " +
                "(`modelId` TEXT NOT NULL, `downloadId` INTEGER NOT NULL, PRIMARY KEY(`modelId`))",
        )
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `segments` ADD COLUMN `isManuallyEdited` INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE `segments` ADD COLUMN `speakerAssignmentEdited` INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `model_downloads` ADD COLUMN `sourceIndex` INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `projects` ADD COLUMN `translationLanguage` TEXT")
        database.execSQL("ALTER TABLE `segments` ADD COLUMN `translatedText` TEXT")
    }
}

@Database(
    entities = [ProjectEntity::class, SegmentEntity::class, SpeakerEntity::class, ModelDownloadEntity::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(ProjectStateConverter::class)
abstract class TranscriptDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao

    companion object {
        fun open(context: Context): TranscriptDatabase = Room.databaseBuilder(
            context.applicationContext,
            TranscriptDatabase::class.java,
            "transcriptor.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}

fun ProjectEntity.toDomain() = TranscriptProject(
    id, title, mediaPath, mediaMimeType, durationMs, modelId, language, autoSpeakers, state, speakerWarning, error,
    progress.coerceIn(0, 100), translationLanguage,
)

fun SegmentEntity.toDomain() = TranscriptSegment(id, projectId, ordinal, startMs, endMs, text, speakerId, translatedText)
fun SpeakerEntity.toDomain() = TranscriptSpeaker(id, projectId, name, clusterId)
