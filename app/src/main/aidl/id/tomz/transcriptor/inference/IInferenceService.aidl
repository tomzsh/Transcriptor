package id.tomz.transcriptor.inference;
import id.tomz.transcriptor.inference.IInferenceCallback;

interface IInferenceService {
    oneway void execute(String requestPath, String resultPath, IInferenceCallback callback);
    oneway void cancel();
}
