package id.tomz.transcriptor.translation

internal data class ComputeBackendDevice(
    val name: String,
    val backend: String,
    val description: String,
) {
    companion object {
        fun fromNativeEntry(entry: String): ComputeBackendDevice? {
            val fields = entry.split('\t', limit = 3)
            if (fields.size < 2 || fields[0].isBlank() || fields[1].isBlank()) return null
            return ComputeBackendDevice(
                name = fields[1],
                backend = fields[0],
                description = fields.getOrElse(2) { "" },
            )
        }
    }
}

internal object ComputeBackendPolicy {
    fun choose(devices: List<ComputeBackendDevice>): ComputeBackendDevice? = devices
        .filter { rank(it) < Int.MAX_VALUE }
        .minWithOrNull(compareBy<ComputeBackendDevice>({ rank(it) }, { it.name }))

    private fun rank(device: ComputeBackendDevice): Int {
        val backend = device.backend.lowercase()
        val hardware = "${device.name} ${device.description}".lowercase()
        return when {
            backend.contains("hexagon") || backend.contains("htp") || hardware.contains("hexagon") -> 0
            backend.contains("opencl") && hardware.contains("adreno") -> 1
            backend.contains("vulkan") -> 2
            backend.contains("opencl") -> 3
            backend.contains("cpu") -> Int.MAX_VALUE
            else -> 4
        }
    }
}

internal data class ComputeAccelerationInfo(
    val devices: List<ComputeBackendDevice>,
    val nnapiAccelerators: List<String>,
    val openClDriverDetected: Boolean,
    val nativeRuntimeAvailable: Boolean,
)

internal object ComputeAccelerationDetector {
    fun detect(includeGpu: Boolean = true): ComputeAccelerationInfo = try {
        ComputeAccelerationInfo(
            devices = if (includeGpu) LlamaNative.getAvailableBackends().mapNotNull { ComputeBackendDevice.fromNativeEntry(it) } else emptyList(),
            nnapiAccelerators = LlamaNative.getNnapiAccelerators().filter { it.isNotBlank() },
            openClDriverDetected = LlamaNative.isOpenClAvailable(),
            nativeRuntimeAvailable = true,
        )
    } catch (_: Throwable) {
        ComputeAccelerationInfo(emptyList(), emptyList(), openClDriverDetected = false, nativeRuntimeAvailable = false)
    }

    fun chooseTranslationBackend(): ComputeBackendDevice? = runCatching {
        LlamaNative.getAvailableBackends()
            .mapNotNull { ComputeBackendDevice.fromNativeEntry(it) }
            .let { ComputeBackendPolicy.choose(it) }
    }.getOrNull()
}
