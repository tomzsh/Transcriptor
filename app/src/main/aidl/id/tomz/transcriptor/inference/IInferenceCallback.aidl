package id.tomz.transcriptor.inference;

oneway interface IInferenceCallback {
    void onProgress(int value);
    void onCompleted();
    void onError(String message);
}
