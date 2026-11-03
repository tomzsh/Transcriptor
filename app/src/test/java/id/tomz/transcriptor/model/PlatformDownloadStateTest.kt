package id.tomz.transcriptor.model

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDownloadStateTest {
    @Test fun mapsQueuedRunningAndPausedSystemDownloads() {
        assertEquals(PlatformDownloadState.QUEUED, platformDownloadState(DownloadManager.STATUS_PENDING))
        assertEquals(PlatformDownloadState.RUNNING, platformDownloadState(DownloadManager.STATUS_RUNNING))
        assertEquals(PlatformDownloadState.PAUSED, platformDownloadState(DownloadManager.STATUS_PAUSED))
    }

    @Test fun terminalSystemStatesAreNotShownAsInProgress() {
        assertNull(platformDownloadState(DownloadManager.STATUS_SUCCESSFUL))
        assertNull(platformDownloadState(DownloadManager.STATUS_FAILED))
    }
    @Test fun modelDownloadSourcesFollowMirrorThenPinnedUpstreamFallback() {
        val whisper = requireNotNull(ModelCatalog.get("whisper-tiny"))
        val segmentation = requireNotNull(ModelCatalog.get("speaker-segmentation"))

        assertEquals(
            listOf(requireNotNull(whisper.mirrorUrl), whisper.upstreamUrl),
            modelDownloadSources(whisper),
        )
        assertEquals(listOf(segmentation.upstreamUrl), modelDownloadSources(segmentation))
    }
    @Test fun retriesAnotherSourceForHttpErrorsButNotLocalStorageFailures() {
        assertTrue(shouldTryNextDownloadSource(DownloadManager.ERROR_UNHANDLED_HTTP_CODE))
        assertTrue(shouldTryNextDownloadSource(DownloadManager.ERROR_HTTP_DATA_ERROR))
        assertFalse(shouldTryNextDownloadSource(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertFalse(shouldTryNextDownloadSource(DownloadManager.ERROR_FILE_ERROR))
    }
}
