package id.tomz.transcriptor.data

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradePersistenceTest {
    @Test fun versionFourMigrationKeepsExistingProjectsCuesAndSpeakers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upgrade-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, mediaPath TEXT NOT NULL, mediaMimeType TEXT NOT NULL, durationMs INTEGER NOT NULL, modelId TEXT NOT NULL, language TEXT NOT NULL, autoSpeakers INTEGER NOT NULL, state TEXT NOT NULL, progress INTEGER NOT NULL, error TEXT, speakerWarning TEXT, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX index_projects_createdAt ON projects(createdAt)")
            old.execSQL("CREATE TABLE speakers (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, name TEXT NOT NULL, clusterId INTEGER, FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_speakers_projectId ON speakers(projectId)")
            old.execSQL("CREATE UNIQUE INDEX index_speakers_projectId_clusterId ON speakers(projectId, clusterId)")
            old.execSQL("CREATE TABLE segments (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, ordinal INTEGER NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, speakerId TEXT, isManuallyEdited INTEGER NOT NULL DEFAULT 0, speakerAssignmentEdited INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(projectId) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(speakerId) REFERENCES speakers(id) ON DELETE SET NULL)")
            old.execSQL("CREATE INDEX index_segments_projectId ON segments(projectId)")
            old.execSQL("CREATE INDEX index_segments_speakerId ON segments(speakerId)")
            old.execSQL("CREATE UNIQUE INDEX index_segments_projectId_ordinal ON segments(projectId, ordinal)")
            old.execSQL("CREATE TABLE model_downloads (modelId TEXT NOT NULL PRIMARY KEY, downloadId INTEGER NOT NULL, sourceIndex INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("INSERT INTO projects VALUES ('p','Old project','/private/source.wav','audio/wav',3000,'whisper-tiny','en',0,'COMPLETED',100,NULL,NULL,1)")
            old.execSQL("INSERT INTO speakers VALUES ('speaker','p','Ayu',0)")
            old.execSQL("INSERT INTO segments VALUES ('s','p',0,100,2000,'Original words','speaker',1,1)")
            old.version = 4
        }
        val db = Room.databaseBuilder(context, TranscriptDatabase::class.java, name).addMigrations(MIGRATION_4_5).build()
        try {
            val aggregate = db.transcriptDao().observeProject("p").first()!!
            assertEquals("Old project", aggregate.project.title)
            assertEquals("Original words", aggregate.segments.single().text)
            assertEquals("Ayu", aggregate.speakers.single().name)
            assertEquals(true, aggregate.segments.single().isManuallyEdited)
            assertNull(aggregate.project.translationLanguage)
            assertNull(aggregate.segments.single().translatedText)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun stageProgressIsMonotonicAndLateCallbacksCannotChangeCompletedProject() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val dao = db.transcriptDao()
            dao.insertProject(project())
            dao.claimProjectForProcessing("p")
            assertEquals(1, dao.updateProgress("p", ProjectState.DECODING, 45))
            assertEquals(0, dao.updateProgress("p", ProjectState.DECODING, 10))
            assertEquals(0, dao.updateProgress("p", ProjectState.TRANSCRIBING, 90))
            assertEquals(45, dao.project("p")!!.toDomain().progress)
            dao.updateProject(dao.project("p")!!.copy(state = ProjectState.COMPLETED, progress = 100))
            assertEquals(0, dao.updateProgress("p", ProjectState.DECODING, 99))
            assertEquals(ProjectState.COMPLETED, dao.project("p")!!.state)
        } finally { db.close() }
    }

    @Test fun translationPreservesOriginalTimingAndInvalidatesWhenTextChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val dao = db.transcriptDao()
            dao.insertProject(project())
            dao.putSegments(listOf(SegmentEntity("s", "p", 0, 100, 2000, "Hello", null)))
            val claimed = dao.claimProjectForTranslation("p")!!
            assertNull(dao.claimProjectForProcessing("p"))
            assertNull(dao.claimProjectForDeletion("p"))
            assertEquals(SegmentEditError.PROJECT_BUSY, dao.updateSegmentChecked(dao.segments("p").single().copy(text = "Changed")))
            dao.updateProject(claimed.copy(state = ProjectState.TRANSLATING))
            dao.saveTranslation("p", "id", dao.segments("p"), listOf("Halo"))
            var aggregate = dao.observeProject("p").first()!!
            assertEquals("Hello", aggregate.segments.single().text)
            assertEquals("Halo", aggregate.segments.single().translatedText)
            assertEquals(100L, aggregate.segments.single().startMs)
            assertEquals("id", aggregate.project.translationLanguage)
            assertEquals(ProjectState.COMPLETED, aggregate.project.state)
            assertNull(dao.updateSegmentChecked(aggregate.segments.single().copy(startMs = 200)))
            assertEquals("Halo", dao.segments("p").single().translatedText)
            assertNull(dao.updateSegmentChecked(dao.segments("p").single().copy(text = "Good morning")))
            aggregate = dao.observeProject("p").first()!!
            val document = TranscriptDocument(aggregate.project.toDomain(), aggregate.segments.map { it.toDomain() }, emptyList())
            assertNull(document.translatedDocument())
            assertEquals("Good morning", document.segments.single().text)
        } finally { db.close() }
    }

    @Test fun translationCommitRejectsIncompleteAndStaleResults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val dao = db.transcriptDao()
            dao.insertProject(project().copy(state = ProjectState.TRANSLATING))
            val cue = SegmentEntity("s", "p", 0, 100, 2000, "Hello", null)
            dao.putSegments(listOf(cue))
            assertTrue(runCatching { dao.saveTranslation("p", "id", listOf(cue), emptyList()) }.isFailure)
            assertTrue(runCatching { dao.saveTranslation("p", "id", listOf(cue.copy(text = "Stale")), listOf("Halo")) }.isFailure)
            assertNull(dao.segments("p").single().translatedText)
            assertNull(dao.project("p")!!.translationLanguage)
        } finally { db.close() }
    }

    private fun project() = ProjectEntity("p", "Audio", "source.wav", "audio/wav", 3000,
        "whisper-tiny", "en", false, ProjectState.COMPLETED, createdAt = 1)
}
