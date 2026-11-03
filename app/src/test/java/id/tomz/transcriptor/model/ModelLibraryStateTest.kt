package id.tomz.transcriptor.model

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLibraryStateTest {
    @Test fun verifiedDestinationIsShownAsInstalledAfterCancellation() {
        val directory = Files.createTempDirectory("model-cancel-state").toFile()
        val expected = "abc".toByteArray()
        val spec = fixtureSpec(expected)
        directory.resolve(spec.fileName).writeBytes(expected)
        try {
            val initial = ModelLibraryState(entries = listOf(ModelLibraryEntryState(spec)))

            val verified = verifiedInstalledModels(directory, listOf(spec)).getValue(spec.id)
            assertTrue(verified)
            val reconciled = initial.reconcileInstalledModel(spec.id, verified)

            assertTrue(reconciled.entries.single().installed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun destinationWithMatchingSizeButWrongDigestIsNotShownAsInstalled() {
        val directory = Files.createTempDirectory("model-cancel-corrupt").toFile()
        val expected = "abc".toByteArray()
        val spec = fixtureSpec(expected)
        directory.resolve(spec.fileName).writeBytes("abd".toByteArray())
        try {
            val initiallyInstalled = ModelLibraryState(
                entries = listOf(ModelLibraryEntryState(spec, installed = true)),
            )

            val verified = verifiedInstalledModels(directory, listOf(spec)).getValue(spec.id)
            assertFalse(verified)
            val reconciled = initiallyInstalled.reconcileInstalledModel(spec.id, verified)

            assertFalse(reconciled.entries.single().installed)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun fixtureSpec(bytes: ByteArray) = ModelSpec(
        id = "fixture",
        label = "Fixture",
        fileName = "fixture.bin",
        mirrorUrl = null,
        upstreamUrl = "https://example.invalid/fixture.bin",
        bytes = bytes.size.toLong(),
        sha256 = bytes.sha256(),
        englishOnly = false,
        licence = "MIT",
        kind = ModelKind.WHISPER,
    )

    private fun ByteArray.sha256() = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
