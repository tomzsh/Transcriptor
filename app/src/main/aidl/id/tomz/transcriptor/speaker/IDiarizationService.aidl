package id.tomz.transcriptor.speaker;

import id.tomz.transcriptor.speaker.IDiarizationCallback;

interface IDiarizationService {
    void start(String pcmPath, String segmentationPath, String embeddingPath, String resultPath, IDiarizationCallback callback);
    void cancel();
}
