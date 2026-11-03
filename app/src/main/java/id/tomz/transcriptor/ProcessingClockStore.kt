package id.tomz.transcriptor

import id.tomz.transcriptor.domain.ProjectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProcessingStageClock(val state: ProjectState, val startedAtElapsedMs: Long)

/** Process-local stage starts survive navigation without per-second database writes. */
internal class ProcessingClockStore(private val elapsedRealtime: () -> Long) {
    private val mutableStages = MutableStateFlow<Map<String, ProcessingStageClock>>(emptyMap())
    val stages = mutableStages.asStateFlow()

    fun begin(projectId: String, state: ProjectState) {
        val stage = ProcessingStageClock(state, elapsedRealtime())
        mutableStages.update { clocks ->
            if (clocks[projectId]?.state == state) clocks else clocks + (projectId to stage)
        }
    }

    fun clear(projectId: String) {
        mutableStages.update { clocks -> clocks - projectId }
    }
}
