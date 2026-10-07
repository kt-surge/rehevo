package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;

import java.util.List;
import java.util.function.Consumer;

/**
 * Voice handler boundary for normal and sentence-streaming LLM replies.
 */
public interface VoiceLlmClient {

    String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory);

    DashscopeLlmService.VoiceLlmResponse chatStreamSentences(
        String userInput,
        Consumer<String> onToken,
        Consumer<String> onSentence,
        VoiceInterviewSessionEntity session,
        List<String> conversationHistory);
}
