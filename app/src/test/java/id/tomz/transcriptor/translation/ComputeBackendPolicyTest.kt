package id.tomz.transcriptor.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeBackendPolicyTest {
    @Test fun parsesBackendDeviceMetadataWithoutSplittingDeviceName() {
        assertEquals(
            ComputeBackendDevice("Mali-G615 MC6", "Vulkan", "Mali-G615 MC6"),
            ComputeBackendDevice.fromNativeEntry("Vulkan\tMali-G615 MC6\tMali-G615 MC6"),
        )
        assertNull(ComputeBackendDevice.fromNativeEntry("\t\t"))
    }

    @Test fun prefersHexagonNpuWhenTheRuntimeProvidesIt() {
        val devices = listOf(
            ComputeBackendDevice("Vulkan0", "Vulkan", "Adreno 740"),
            ComputeBackendDevice("OpenCL0", "OpenCL", "Adreno 740"),
            ComputeBackendDevice("HTP0", "Hexagon", "Qualcomm Hexagon"),
        )

        assertEquals("HTP0", ComputeBackendPolicy.choose(devices)?.name)
    }

    @Test fun prefersAdrenoOpenClOverVulkanWhenBothAreAvailable() {
        val devices = listOf(
            ComputeBackendDevice("Vulkan0", "Vulkan", "Adreno 740"),
            ComputeBackendDevice("OpenCL0", "OpenCL", "Adreno 740"),
        )

        assertEquals("OpenCL0", ComputeBackendPolicy.choose(devices)?.name)
    }

    @Test fun prefersVulkanOverNonAdrenoOpenCl() {
        val devices = listOf(
            ComputeBackendDevice("OpenCL0", "OpenCL", "Mali-G615 MC6"),
            ComputeBackendDevice("Vulkan0", "Vulkan", "Mali-G615 MC6"),
        )

        assertEquals("Vulkan0", ComputeBackendPolicy.choose(devices)?.name)
    }

    @Test fun returnsNoAcceleratorWhenOnlyCpuIsAvailable() {
        assertNull(ComputeBackendPolicy.choose(emptyList()))
    }
}
