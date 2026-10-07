package interview.guide.modules.voiceinterview.service;

/**
 * Voice handler boundary for TTS synthesis.
 * A fake implementation can return delayed, empty or failed audio deterministically.
 */
public interface VoiceTtsClient {

    byte[] synthesize(String text);
}
