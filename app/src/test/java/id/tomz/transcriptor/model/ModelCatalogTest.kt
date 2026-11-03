package id.tomz.transcriptor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun indonesianFineTuneHasItsOwnVerifiedDownloadAndAttribution() {
        val model = requireNotNull(ModelCatalog.get("whisper-cahya-medium-id-q5"))
        assertEquals(ModelKind.WHISPER, model.kind)
        assertEquals("ggml-cahya-medium-id-q5_0.bin", model.fileName)
        assertEquals(539_212_467L, model.bytes)
        assertEquals("da09c51f05f78a727d37325e2d1ad7b78c20597593215276e820cd7fa1e92e0f", model.sha256)
        assertEquals(null, model.mirrorUrl)
        assertEquals(
            "https://huggingface.co/rafdiraf/whisper-medium-id-ggml/resolve/674030cf4477a23e279fbc95ffa993b08db02269/ggml-model-medium-id-q5_0.bin",
            model.upstreamUrl,
        )
        assertTrue(!model.englishOnly)
        assertEquals("id", model.transcriptionLanguage)
        assertTrue(model.licence.contains("Apache-2.0"))
        assertTrue(model.licence.contains("Cahya"))
        assertTrue(model.licence.contains("rafdiraf"))
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", model.licenceUrl)
        assertTrue(model.description.contains("https://huggingface.co/cahya/whisper-medium-id"))
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
    }

    @Test fun whisperEntriesMatchConcatManifestAndPinnedUpstreamRevision() {
        assertEquals(
            listOf("whisper-tiny", "whisper-tiny.en", "whisper-base", "whisper-base.en", "whisper-small", "whisper-small.en", "whisper-small-q5", "whisper-turbo-q5", "whisper-cahya-medium-id-q5"),
            ModelCatalog.whisperModels.map { it.id },
        )
        val tiny = ModelCatalog.get("whisper-tiny")!!
        assertEquals("ggml-tiny.bin", tiny.fileName)
        assertEquals(77_691_713L, tiny.bytes)
        assertEquals("be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", tiny.sha256)
        assertEquals(
            "https://github.com/jub0t/Concat/releases/download/models-v1/ggml-tiny.bin",
            tiny.mirrorUrl,
        )
        assertTrue(tiny.upstreamUrl.contains("5359861c739e955e79d9a303bcbc70fb988958b1"))
        assertTrue(tiny.upstreamUrl.endsWith("/ggml-tiny.bin"))
        assertEquals(9, ModelCatalog.whisperModels.count { it.kind == ModelKind.WHISPER })
        assertTrue(ModelCatalog.whisperModels.filter { it.englishOnly }.all { it.id.endsWith(".en") })
    }

    @Test fun mobileQualityModelsAreMultilingualAndUsePinnedVerifiedDownloads() {
        val small = ModelCatalog.get("whisper-small-q5")!!
        val turbo = ModelCatalog.get("whisper-turbo-q5")!!
        assertTrue(!small.englishOnly && !turbo.englishOnly)
        assertTrue(small.bytes < ModelCatalog.get("whisper-small")!!.bytes / 2)
        assertEquals(574_041_195L, turbo.bytes)
        for (model in listOf(small, turbo)) {
            assertEquals(null, model.mirrorUrl)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.upstreamUrl.contains(ModelCatalog.WHISPER_UPSTREAM_REVISION))
        }
    }

    @Test fun offlineDiarizationAssetsHavePinnedVerifiedPayloads() {
        val segmentation = ModelCatalog.get("speaker-segmentation")!!
        assertEquals(ModelKind.DIARIZATION_SEGMENTATION, segmentation.kind)
        assertEquals(1_540_506L, segmentation.bytes)
        assertEquals("d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d", segmentation.sha256)
        assertEquals("sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx", segmentation.archiveMember)
        assertEquals(6_958_444L, segmentation.downloadBytes)
        assertEquals("24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488", segmentation.downloadSha256)

        val embedding = ModelCatalog.get("speaker-embedding")!!
        assertEquals(ModelKind.DIARIZATION_EMBEDDING, embedding.kind)
        assertEquals(28_281_164L, embedding.bytes)
        assertEquals("aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2", embedding.sha256)
    }
}
