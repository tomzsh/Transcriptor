package id.tomz.transcriptor.model

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedModelInstallerTest {
    private val spec = ModelSpec(
        id = "fixture", label = "Fixture", fileName = "fixture.bin",
        mirrorUrl = "https://example.invalid/mirror/fixture.bin",
        upstreamUrl = "https://example.invalid/upstream/fixture.bin",
        bytes = 3, sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        englishOnly = false, licence = "MIT", kind = ModelKind.WHISPER,
    )

    @Test fun atomicallyInstallsExactBytesAfterChecksumVerification() {
        val directory = Files.createTempDirectory("model-install").toFile()
        val partial = directory.resolve("fixture.part").apply { writeBytes("abc".toByteArray()) }
        val destination = directory.resolve("fixture.bin")
        VerifiedModelInstaller.install(partial, destination, spec)
        assertTrue(destination.isFile)
        assertFalse(partial.exists())
        assertArrayEquals("abc".toByteArray(), destination.readBytes())
        directory.deleteRecursively()
    }

    @Test fun rejectsWrongSizeAndDigestWithoutLeavingUsableFile() {
        val directory = Files.createTempDirectory("model-invalid").toFile()
        val partial = directory.resolve("fixture.part").apply { writeBytes("abd".toByteArray()) }
        val destination = directory.resolve("fixture.bin")
        try {
            VerifiedModelInstaller.install(partial, destination, spec)
            throw AssertionError("wrong model unexpectedly installed")
        } catch (_: InvalidModelException) {
        }
        assertFalse(destination.exists())
        assertFalse(partial.exists())
        directory.deleteRecursively()
    }
}
