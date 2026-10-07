package interview.guide.modules.voiceinterview.service;

import java.util.function.Consumer;

/**
 * Voice handler boundary for streaming ASR.
 * Production uses DashScope; tests may provide deterministic callbacks without an API key.
 */
public interface VoiceAsrClient {

    void startTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError);

    void restartTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError);

    void sendAudio(String sessionId, byte[] audioData);

    void stopTranscription(String sessionId);

    boolean isReady(String sessionId);
}
