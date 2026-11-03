package id.tomz.transcriptor.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualSpeakerRepositoryTest {
    @Test fun speakerChangesAreRejectedWhileProcessingOrDeleting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java).build()
        try {
            val dao = database.transcriptDao()
            val project = ProjectEntity("speaker-guard", "Interview", "/private/test.wav", "audio/wav",
                10_000, "whisper-tiny", "en", false, ProjectState.COMPLETED, createdAt = 1)
            dao.insertProject(project)
            val repository = MediaProjectRepository(context, dao)
            val speaker = requireNotNull(repository.createSpeaker(project.id, "Original"))
            for (state in listOf(ProjectState.TRANSCRIBING, ProjectState.TRANSLATING, ProjectState.DELETING)) {
                dao.updateProject(project.copy(state = state))
                assertNull("Creation must respect $state", repository.createSpeaker(project.id, "New"))
                assertFalse("Renaming must respect $state", repository.renameSpeaker(speaker.id, "Changed"))
                assertEquals("Original", dao.speaker(speaker.id)?.name)
            }
        } finally { database.close() }
    }

    @Test fun createsNamedSpeakerAndAllowsAssigningItToASavedCue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "manual-speaker-project",
                    title = "Interview",
                    mediaPath = "/private/interview.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 10_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = false,
                    state = ProjectState.COMPLETED,
                    createdAt = 7,
                ),
            )
            dao.putSegments(listOf(SegmentEntity("cue", "manual-speaker-project", 0, 0, 1_000, "Hello", null)))
            val repository = MediaProjectRepository(context, dao)

            val speaker = repository.createSpeaker("manual-speaker-project", "  Interviewer  ")
            assertNotNull(speaker)
            assertEquals("Interviewer", speaker!!.name)
            assertNull(repository.updateSegment(TranscriptSegment("cue", "manual-speaker-project", 0, 0, 1_000, "Hello", speaker.id)))
            assertEquals(speaker.id, dao.segments("manual-speaker-project").single().speakerId)

            assertNull(repository.createSpeaker("manual-speaker-project", "  "))
        } finally {
            database.close()
        }
    }
}
