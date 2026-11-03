package id.tomz.transcriptor.data
import android.content.ContextWrapper

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import id.tomz.transcriptor.domain.ProjectState
import id.tomz.transcriptor.domain.SegmentEditError
import id.tomz.transcriptor.speaker.SpeakerTurn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptDatabaseTest {
    @Test fun transcriptReplacementAndProjectDeletionRemainConsistent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "project-1",
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = false,
                    state = ProjectState.IMPORTED,
                    createdAt = 1,
                ),
            )
            dao.putSpeakers(listOf(SpeakerEntity("project-1:speaker-1", "project-1", "Ayu", 7)))
            dao.putSegments(listOf(SegmentEntity("s1", "project-1", 0, 0, 1_000, "Draft", "project-1:speaker-1")))

            val before = dao.observeProject("project-1").first()!!
            assertEquals("Draft", before.segments.single().text)
            assertEquals("Ayu", before.speakers.single().name)

            dao.replaceTranscript(
                "project-1",
                listOf(SpeakerEntity("project-1:speaker-2", "project-1", "Budi", 3)),
                listOf(SegmentEntity("s2", "project-1", 0, 1_000, 2_000, "Saved edit", "project-1:speaker-2")),
            )
            val after = dao.observeProject("project-1").first()!!
            assertEquals(listOf("Saved edit"), after.segments.map { it.text })
            assertEquals(setOf("Ayu", "Budi"), after.speakers.map { it.name }.toSet())

            val processingClaim = dao.claimProjectForProcessing("project-1")
            assertNotNull(processingClaim)
            assertNull(dao.claimProjectForDeletion("project-1"))
            assertEquals(ProjectState.DECODING, dao.project("project-1")?.state)

            dao.updateProject(processingClaim!!.copy(state = ProjectState.IMPORTED))
            val deletionClaim = dao.claimProjectForDeletion("project-1")
            assertEquals(ProjectState.IMPORTED, deletionClaim?.state)
            assertEquals(ProjectState.DELETING, dao.project("project-1")?.state)
            assertEquals(1, dao.deleteClaimedProject("project-1"))
            assertNull(dao.project("project-1"))
            assertEquals(emptyList<Any>(), dao.observeAllProjects().first())
        } finally {
            database.close()
        }
    }

    @Test fun rerunningTranscriptPreservesEditedCuesAndSpeakerOverrides() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            val projectId = "project-rerun"
            val detectedSpeaker = SpeakerEntity("$projectId:speaker-1", projectId, "Host", 4)
            val manualSpeaker = SpeakerEntity("$projectId:manual-speaker", projectId, "Guest", null)
            dao.insertProject(
                ProjectEntity(
                    id = projectId,
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = true,
                    state = ProjectState.COMPLETED,
                    createdAt = 10,
                ),
            )
            dao.putSpeakers(listOf(detectedSpeaker, manualSpeaker))
            dao.putSegments(
                listOf(
                    SegmentEntity("edited", projectId, 0, 1_100, 1_500, "Draft", detectedSpeaker.id),
                    SegmentEntity("old-auto", projectId, 1, 1_600, 2_000, "Old words", null),
                ),
            )
            assertNull(
                dao.updateSegmentChecked(
                    SegmentEntity("edited", projectId, 0, 1_000, 1_550, "Hand corrected", manualSpeaker.id),
                ),
            )

            dao.replaceTranscript(
                projectId,
                emptyList(),
                listOf(
                    SegmentEntity("fresh", projectId, 0, 100, 500, "Fresh words", null),
                    SegmentEntity("overlap", projectId, 1, 1_200, 1_700, "Replace this", null),
                    SegmentEntity("tail", projectId, 2, 1_800, 2_200, "New tail", null),
                ),
            )
            dao.applyDiarization(
                projectId,
                listOf(detectedSpeaker.copy(name = "Speaker 1")),
                listOf(SpeakerTurn(1_000, 1_550, 4)),
            )

            val saved = dao.observeProject(projectId).first()!!
            assertEquals(listOf("Fresh words", "Hand corrected", "New tail"), saved.segments.map { it.text })
            assertEquals(listOf(0, 1, 2), saved.segments.map { it.ordinal })
            val edited = saved.segments[1]
            assertEquals(1_000L, edited.startMs)
            assertEquals(1_550L, edited.endMs)
            assertEquals(true, edited.isManuallyEdited)
            assertEquals(true, edited.speakerAssignmentEdited)
            assertEquals(manualSpeaker.id, edited.speakerId)
            assertEquals("Host", saved.speakers.single { it.id == detectedSpeaker.id }.name)
        } finally {
            database.close()
        }
    }

    @Test fun startupRecoveryRunsOnlyOncePerRepositoryInstance() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = File(targetContext.cacheDir, "startup-${UUID.randomUUID()}").apply { mkdirs() }
        val privateContext = object : ContextWrapper(targetContext) {
            override fun getFilesDir(): File = filesRoot
        }
        val database = Room.inMemoryDatabaseBuilder(targetContext, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "startup-project",
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = false,
                    state = ProjectState.IMPORTED,
                    createdAt = 1,
                ),
            )
            val repository = MediaProjectRepository(privateContext, dao)
            repository.prepareForStartup()
            assertNotNull(dao.claimProjectForProcessing("startup-project"))

            repository.prepareForStartup()

            assertEquals(ProjectState.DECODING, dao.project("startup-project")?.state)
        } finally {
            database.close()
            filesRoot.deleteRecursively()
        }
    }
    @Test fun platformDownloadIdIsStoredAndRemovedByModelId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            val download = ModelDownloadEntity(modelId = "whisper-tiny", downloadId = 901L, sourceIndex = 1)

            dao.putModelDownload(download)

            assertEquals(download, dao.modelDownload("whisper-tiny"))
            assertEquals(listOf(download), dao.modelDownloads())
            dao.deleteModelDownload("whisper-tiny")
            assertNull(dao.modelDownload("whisper-tiny"))
        } finally {
            database.close()
        }
    }


    @Test fun cueAndSpeakerEditsPersistInTheProjectAggregate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "project-edit",
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = true,
                    state = ProjectState.COMPLETED,
                    createdAt = 2,
                ),
            )
            dao.putSpeakers(listOf(SpeakerEntity("project-edit:speaker-1", "project-edit", "Pembicara 1", 4)))
            dao.putSegments(listOf(SegmentEntity("cue-edit", "project-edit", 0, 100, 900, "Draft", "project-edit:speaker-1")))

            assertNull(
                dao.updateSegmentChecked(
                    SegmentEntity("cue-edit", "project-edit", 0, 125, 975, "Teks tersunting", "project-edit:speaker-1"),
                ),
            )
            dao.updateSpeaker(SpeakerEntity("project-edit:speaker-1", "project-edit", "Narasumber", 4))

            val saved = dao.observeProject("project-edit").first()!!
            assertEquals("Teks tersunting", saved.segments.single().text)
            assertEquals(125L, saved.segments.single().startMs)
            assertEquals(975L, saved.segments.single().endMs)
            assertEquals("Narasumber", saved.speakers.single().name)
        } finally {
            database.close()
        }
    }
    @Test fun diarizationReassignsOnlySpeakersOnCurrentSavedSegments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "project-diarization",
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = true,
                    state = ProjectState.DIARIZING,
                    createdAt = 3,
                ),
            )
            dao.putSpeakers(listOf(SpeakerEntity("project-diarization:speaker-1", "project-diarization", "Host", 9)))
            dao.putSegments(
                listOf(SegmentEntity("edited-cue", "project-diarization", 0, 400, 600, "Edited words", null)),
            )
            val turns = listOf(SpeakerTurn(0, 1_000, 4), SpeakerTurn(0, 500, 9))

            dao.applyDiarization(
                "project-diarization",
                listOf(
                    SpeakerEntity("project-diarization:speaker-1", "project-diarization", "Pembicara 1", 9),
                    SpeakerEntity("project-diarization:speaker-2", "project-diarization", "Pembicara 2", 4),
                ),
                turns,
            )

            val saved = dao.observeProject("project-diarization").first()!!
            val cue = saved.segments.single()
            assertEquals(400L, cue.startMs)
            assertEquals(600L, cue.endMs)
            assertEquals("Edited words", cue.text)
            assertEquals("project-diarization:speaker-2", cue.speakerId)
            assertEquals("Host", saved.speakers.first { it.id.endsWith("speaker-1") }.name)
        } finally {
            database.close()
        }
    }

    @Test fun atomicSegmentEditRejectsNeighborOverlapAfterPriorEdit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            dao.insertProject(
                ProjectEntity(
                    id = "project-atomic",
                    title = "Meeting",
                    mediaPath = "/private/meeting.wav",
                    mediaMimeType = "audio/wav",
                    durationMs = 5_000,
                    modelId = "whisper-tiny",
                    language = "en",
                    autoSpeakers = false,
                    state = ProjectState.COMPLETED,
                    createdAt = 4,
                ),
            )
            dao.putSegments(
                listOf(
                    SegmentEntity("first", "project-atomic", 0, 0, 1_000, "First", null),
                    SegmentEntity("second", "project-atomic", 1, 1_100, 2_000, "Second", null),
                ),
            )

            val extendFirst = dao.updateSegmentChecked(
                SegmentEntity("first", "project-atomic", 0, 0, 1_100, "First", null),
            )
            val moveSecondEarlier = dao.updateSegmentChecked(
                SegmentEntity("second", "project-atomic", 1, 1_000, 2_000, "Second", null),
            )

            assertEquals(null, extendFirst)
            assertEquals(SegmentEditError.OVERLAPS_NEIGHBOR, moveSecondEarlier)
            val saved = dao.segments("project-atomic")
            assertEquals(false, saved[0].endMs > saved[1].startMs)
        } finally {
            database.close()
        }
    }

    @Test fun abandonedActiveProjectsBecomeRestartableWithoutChangingCompletedOnes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            for ((id, state) in listOf(
                "decoding" to ProjectState.DECODING,
                "transcribing" to ProjectState.TRANSCRIBING,
                "diarizing" to ProjectState.DIARIZING,
                "complete" to ProjectState.COMPLETED,
            )) {
                dao.insertProject(
                    ProjectEntity(
                        id = id,
                        title = id,
                        mediaPath = "/private/$id.wav",
                        mediaMimeType = "audio/wav",
                        durationMs = 5_000,
                        modelId = "whisper-tiny",
                        language = "en",
                        autoSpeakers = false,
                        state = state,
                        createdAt = 5,
                    ),
                )
            }

            dao.recoverAbandonedProjects()

            assertEquals(ProjectState.INTERRUPTED, dao.project("decoding")!!.state)
            assertEquals(ProjectState.INTERRUPTED, dao.project("transcribing")!!.state)
            assertEquals(ProjectState.INTERRUPTED, dao.project("diarizing")!!.state)
            assertEquals(ProjectState.COMPLETED, dao.project("complete")!!.state)
        } finally {
            database.close()
        }
    }

    @Test fun deletionNeverRemovesAnActiveProject() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TranscriptDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.transcriptDao()
            val project = ProjectEntity(
                id = "active-project",
                title = "Meeting",
                mediaPath = "/private/meeting.wav",
                mediaMimeType = "audio/wav",
                durationMs = 5_000,
                modelId = "whisper-tiny",
                language = "en",
                autoSpeakers = false,
                state = ProjectState.TRANSCRIBING,
                createdAt = 4,
            )
            dao.insertProject(project)

            assertNull(dao.claimProjectForDeletion(project.id))
            assertEquals(project, dao.project(project.id))

            val inactive = project.copy(state = ProjectState.IMPORTED)
            dao.updateProject(inactive)
            assertEquals(inactive, dao.claimProjectForDeletion(project.id))
            assertEquals(ProjectState.DELETING, dao.project(project.id)?.state)
            assertEquals(1, dao.deleteClaimedProject(project.id))
            assertNull(dao.project(project.id))
        } finally {
            database.close()
        }
    }
}
