package id.tomz.transcriptor.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaSourceStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `copies media stream to a sanitized project-local source file`() {
        val projectDirectory = temporaryFolder.newFolder("project")
        val bytes = byteArrayOf(0, 1, 2, 3, 4)

        val source = copyMediaSourceToProject(
            ByteArrayInputStream(bytes),
            projectDirectory,
            "../../Interview WAV.wav",
        )

        assertEquals(File(projectDirectory, "source.wav"), source)
        assertArrayEquals(bytes, source.readBytes())
    }

    @Test
    fun `removes partial source file when input copy fails`() {
        val projectDirectory = temporaryFolder.newFolder("project")
        val brokenInput = object : InputStream() {
            private var first = true

            override fun read(): Int {
                if (first) {
                    first = false
                    return 0x41
                }
                throw IllegalStateException("read failed")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            copyMediaSourceToProject(brokenInput, projectDirectory, "recording.mp3")
        }

        assertFalse(File(projectDirectory, "source.mp3").exists())
    }
}
