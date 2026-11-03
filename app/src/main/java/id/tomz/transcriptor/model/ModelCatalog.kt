package id.tomz.transcriptor.model

/** Pinned, size/hash verified metadata. Model weights are downloaded on demand. */
object ModelCatalog {
    const val WHISPER_RELEASE = "models-v1"
    const val WHISPER_UPSTREAM_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"

    val whisperModels: List<ModelSpec> = listOf(
        whisper("whisper-tiny", "Tiny · multilingual", "ggml-tiny.bin", 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", false),
        whisper("whisper-tiny.en", "Tiny · English only", "ggml-tiny.en.bin", 77_704_715, "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", true),
        whisper("whisper-base", "Base · multilingual", "ggml-base.bin", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", false),
        whisper("whisper-base.en", "Base · English only", "ggml-base.en.bin", 147_964_211, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", true),
        whisper("whisper-small", "Small · multilingual", "ggml-small.bin", 487_601_967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", false),
        whisper("whisper-small.en", "Small · English only", "ggml-small.en.bin", 487_614_201, "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d", true),
        whisper("whisper-small-q5", "Small · multilingual · Q5", "ggml-small-q5_1.bin", 190_085_487,
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", false, mirrored = false),
        whisper("whisper-turbo-q5", "Large v3 Turbo · multilingual · Q5", "ggml-large-v3-turbo-q5_0.bin", 574_041_195,
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", false, mirrored = false),
        ModelSpec(
            id = "whisper-cahya-medium-id-q5",
            label = "Cahya Medium · Indonesia · Q5",
            fileName = "ggml-cahya-medium-id-q5_0.bin",
            mirrorUrl = null,
            upstreamUrl = "https://huggingface.co/rafdiraf/whisper-medium-id-ggml/resolve/674030cf4477a23e279fbc95ffa993b08db02269/ggml-model-medium-id-q5_0.bin",
            bytes = 539_212_467,
            sha256 = "da09c51f05f78a727d37325e2d1ad7b78c20597593215276e820cd7fa1e92e0f",
            englishOnly = false,
            licence = "Apache-2.0 · Cahya · GGML Q5: rafdiraf",
            kind = ModelKind.WHISPER,
            description = "Indonesian fine-tune: https://huggingface.co/cahya/whisper-medium-id; " +
                "community GGML Q5 conversion: https://huggingface.co/rafdiraf/whisper-medium-id-ggml",
            licenceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            transcriptionLanguage = "id",
        ),
    )

    val diarizationModels: List<ModelSpec> = listOf(
        ModelSpec(
            id = "speaker-segmentation",
            label = "Speaker segmentation (int8)",
            fileName = "speaker-segmentation.int8.onnx",
            mirrorUrl = null,
            upstreamUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            bytes = 1_540_506,
            sha256 = "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d",
            englishOnly = false,
            licence = "MIT (CNRS)",
            kind = ModelKind.DIARIZATION_SEGMENTATION,
            downloadBytes = 6_958_444,
            downloadSha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
            archiveMember = "sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx",
        ),
        ModelSpec(
            id = "speaker-embedding",
            label = "Speaker embedding · multilingual",
            fileName = "speaker-embedding.onnx",
            mirrorUrl = null,
            upstreamUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            bytes = 28_281_164,
            sha256 = "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2",
            englishOnly = false,
            licence = "Apache-2.0 (3D-Speaker)",
            kind = ModelKind.DIARIZATION_EMBEDDING,
        ),
    )

    val translationModels: List<ModelSpec> = listOf(
        ai("ai-qwen35-0.8b", "Qwen3.5 · 0.8B · Q4", "Qwen3.5-0.8B-Q4_K_M.gguf",
            "unsloth/Qwen3.5-0.8B-GGUF", "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0", 532_517_120,
            "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", "Apache-2.0",
            "Pilihan paling ringan untuk mulai menerjemahkan. Multilingual; tinjau kembali istilah dan nama.",
            "https://www.apache.org/licenses/LICENSE-2.0"),
        ai("ai-qwen35-2b", "Qwen3.5 · 2B · Q4", "Qwen3.5-2B-Q4_K_M.gguf",
            "unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae", 1_280_835_840,
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", "Apache-2.0",
            "Model lebih besar untuk terjemahan berkonteks. Membutuhkan lebih banyak RAM dan waktu proses.",
            "https://www.apache.org/licenses/LICENSE-2.0"),
        ai("ai-translategemma-4b", "TranslateGemma · 4B · Q4_K_M", "translategemma-4b-it.Q4_K_M.gguf",
            "mradermacher/translategemma-4b-it-GGUF", "d37868f759d2f817a43d28dd80cf9f982ffa62fd", 2_489_909_760,
            "81200d03e843d2ec1ece6eeafe7d13cb6e5211e1fcd336ade55790b683a08330", "Gemma Terms · quant komunitas Q4_K_M",
            "Model khusus terjemahan dengan hasil berkonteks. Unduhan sekitar 2,49 GB; perlu ruang penyimpanan dan RAM yang cukup.",
            "https://ai.google.dev/gemma/terms"),
        ai("ai-gemma4-e2b", "Gemma 4 · E2B · Q4_0", "gemma-4-E2B_q4_0-it.gguf",
            "google/gemma-4-E2B-it-qat-q4_0-gguf", "347eef722ec7f151f37d1ef0b5c7c77d8de4efcb", 3_349_516_256,
            "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", "Apache-2.0",
            "Model Gemma 4 resmi untuk terjemahan berkonteks. Unduhan sekitar 3,35 GB; perlu ruang penyimpanan dan RAM yang cukup.",
            "https://www.apache.org/licenses/LICENSE-2.0"),
        ai("ai-llama32-1b", "Llama 3.2 · 1B · Q4", "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "unsloth/Llama-3.2-1B-Instruct-GGUF", "b69aef112e9f895e6f98d7ae0949f72ff09aa401", 807_694_368,
            "3f5a22426976ab26cfe84dba63c1d08391717abb1af893e10f1b2968d862dcc1", "Llama 3.2 Community",
            "Model instruksi ringan. Dukungan bahasa resminya terbatas; pastikan cocok untuk kebutuhanmu.",
            "https://www.llama.com/llama3_2/license/"),
    )

    val all: List<ModelSpec> = whisperModels + translationModels + diarizationModels

    fun get(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    private fun ai(id: String, label: String, file: String, repo: String, revision: String, bytes: Long,
        sha256: String, licence: String, description: String, licenceUrl: String) = ModelSpec(
        id, label, file, null, "https://huggingface.co/$repo/resolve/$revision/$file", bytes, sha256,
        false, licence, ModelKind.AI_TRANSLATION, description = description, licenceUrl = licenceUrl,
    )

    private fun whisper(id: String, label: String, file: String, bytes: Long, sha256: String, englishOnly: Boolean, mirrored: Boolean = true) =
        ModelSpec(
            id = id,
            label = label,
            fileName = file,
            mirrorUrl = if (mirrored) "https://github.com/jub0t/Concat/releases/download/$WHISPER_RELEASE/$file" else null,
            upstreamUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$WHISPER_UPSTREAM_REVISION/$file",
            bytes = bytes,
            sha256 = sha256,
            englishOnly = englishOnly,
            licence = "MIT",
            kind = ModelKind.WHISPER,
        )
}
