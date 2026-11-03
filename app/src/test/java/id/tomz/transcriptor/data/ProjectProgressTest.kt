package id.tomz.transcriptor.data

import id.tomz.transcriptor.domain.ProjectState
import org.junit.Assert.*
import org.junit.Test

class ProjectProgressTest {
    @Test fun savedStageProgressReachesTheDomainForDisplay() {
        val entity = ProjectEntity("p", "Audio", "a.wav", "audio/wav", 1000, "whisper-tiny", "en", false,
            ProjectState.TRANSCRIBING, progress = 43, createdAt = 1)
        assertEquals(43, entity.toDomain().progress)
        assertEquals(100, entity.copy(progress = 500).toDomain().progress)
        assertEquals(0, entity.copy(progress = -10).toDomain().progress)
    }
}
