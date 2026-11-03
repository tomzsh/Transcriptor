package id.tomz.transcriptor.domain

import java.util.Locale

private val TIMESTAMP_PATTERN = Regex("(\\d{2,}):([0-5]\\d):([0-5]\\d)\\.(\\d{3})")

fun formatTimestamp(milliseconds: Long, separator: Char = '.'): String {
    require(milliseconds >= 0L) { "Timestamps must not be negative" }
    val hours = milliseconds / 3_600_000
    val minutes = milliseconds / 60_000 % 60
    val seconds = milliseconds / 1_000 % 60
    val millis = milliseconds % 1_000
    return "%02d:%02d:%02d%c%03d".format(Locale.ROOT, hours, minutes, seconds, separator, millis)
}

fun parseTimestamp(value: String): Long? {
    val match = TIMESTAMP_PATTERN.matchEntire(value) ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: return null
    val minutes = match.groupValues[2].toLongOrNull() ?: return null
    val seconds = match.groupValues[3].toLongOrNull() ?: return null
    val milliseconds = match.groupValues[4].toLongOrNull() ?: return null
    return try {
        Math.addExact(
            Math.addExact(
                Math.multiplyExact(hours, 3_600_000L),
                Math.multiplyExact(minutes, 60_000L),
            ),
            Math.addExact(Math.multiplyExact(seconds, 1_000L), milliseconds),
        )
    } catch (_: ArithmeticException) {
        null
    }
}
