package id.tomz.transcriptor.speaker

import id.tomz.transcriptor.domain.TranscriptSegment

/** A diarizer cluster's half-open time interval, in milliseconds. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val clusterId: Int) {
    constructor(startMs: Int, endMs: Int, clusterId: Int) : this(startMs.toLong(), endMs.toLong(), clusterId)
}

/** Assigns each cue to the cluster with the largest union-overlap; unmatched cues stay unassigned. */
fun assignSpeakers(
    projectId: String,
    segments: List<TranscriptSegment>,
    turns: List<SpeakerTurn>,
): List<TranscriptSegment> {
    val validTurnsByCluster = turns.filter { it.startMs >= 0 && it.endMs > it.startMs }.groupBy { it.clusterId }
    val orderedClusterIds = orderedSpeakerClusterIds(turns)
    val intervalsByCluster = validTurnsByCluster.mapValues { (_, clusterTurns) -> mergeIntervals(clusterTurns) }
    val speakerIds = orderedClusterIds.mapIndexed { index, clusterId -> clusterId to "$projectId:speaker-${index + 1}" }.toMap()

    return segments.map { segment ->
        if (segment.projectId != projectId || segment.startMs < 0 || segment.endMs <= segment.startMs) {
            return@map segment.copy(speakerId = null)
        }
        var bestCluster: Int? = null
        var bestOverlap = 0L
        for (clusterId in orderedClusterIds) {
            val overlap = intervalsByCluster.getValue(clusterId).sumOf { (start, end) ->
                (minOf(segment.endMs, end) - maxOf(segment.startMs, start)).coerceAtLeast(0L)
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestCluster = clusterId
            }
        }
        segment.copy(speakerId = bestCluster?.let(speakerIds::get))
    }
}

internal fun orderedSpeakerClusterIds(turns: List<SpeakerTurn>): List<Int> {
    val firstAppearances = turns
        .filter { it.startMs >= 0 && it.endMs > it.startMs }
        .groupBy(SpeakerTurn::clusterId)
        .mapValues { (_, clusterTurns) -> clusterTurns.minWith(compareBy<SpeakerTurn> { it.startMs }.thenBy { it.endMs }) }
    return firstAppearances.entries
        .sortedWith(
            compareBy<Map.Entry<Int, SpeakerTurn>> { it.value.startMs }
                .thenBy { it.value.endMs }
                .thenBy { it.key },
        )
        .map { it.key }
}

private fun mergeIntervals(turns: List<SpeakerTurn>): List<Pair<Long, Long>> {
    val sorted = turns.sortedWith(compareBy<SpeakerTurn> { it.startMs }.thenBy { it.endMs })
    val merged = ArrayList<Pair<Long, Long>>(sorted.size)
    for (turn in sorted) {
        val previous = merged.lastOrNull()
        if (previous == null || turn.startMs > previous.second) {
            merged += turn.startMs to turn.endMs
        } else {
            merged[merged.lastIndex] = previous.first to maxOf(previous.second, turn.endMs)
        }
    }
    return merged
}
