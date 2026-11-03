package id.tomz.transcriptor.speaker;

oneway interface IDiarizationCallback {
    void onProgress(int processed, int total);
    void onCompleted(String resultPath);
    void onError(String message);
}
