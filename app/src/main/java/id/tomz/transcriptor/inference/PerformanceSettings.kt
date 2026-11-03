package id.tomz.transcriptor.inference

import android.content.Context
import android.os.Build
import android.os.PowerManager

enum class PerformanceMode { BALANCED, FAST }

class PerformanceSettings(context: Context) {
    private val settings = context.getSharedPreferences("processing", Context.MODE_PRIVATE)
    fun read(): PerformanceMode = settings.getString("performance_mode", null)?.let {
        runCatching { PerformanceMode.valueOf(it) }.getOrNull()
    } ?: if (settings.getBoolean("fast_decoding", false)) PerformanceMode.FAST else PerformanceMode.BALANCED

    fun save(mode: PerformanceMode) {
        settings.edit().putString("performance_mode", mode.name)
            .putBoolean("fast_decoding", mode == PerformanceMode.FAST).apply()
    }
}

internal data class InferencePerformance(val threads: Int, val batchTokens: Int, val priority: Int, val thermalStatus: Int)

internal object PerformancePolicy {
    fun choose(mode: PerformanceMode, processors: Int, thermalStatus: Int): InferencePerformance {
        val cores = processors.coerceAtLeast(1)
        val requested = if (mode == PerformanceMode.FAST) minOf(4, maxOf(1, cores - 1))
            else minOf(3, maxOf(1, cores / 2))
        val threads = when {
            thermalStatus >= 3 -> 1
            thermalStatus >= 2 -> minOf(2, requested)
            else -> requested
        }
        val batch = when {
            thermalStatus >= 3 -> 32
            thermalStatus >= 2 -> 48
            mode == PerformanceMode.FAST -> 128
            else -> 64
        }
        return InferencePerformance(threads, batch, if (mode == PerformanceMode.BALANCED || thermalStatus >= 2) 5 else 0, thermalStatus)
    }

    fun current(context: Context, mode: PerformanceMode): InferencePerformance {
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        }.getOrDefault(0) else 0
        return choose(mode, Runtime.getRuntime().availableProcessors(), thermal)
    }
}
