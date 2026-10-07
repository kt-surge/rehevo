package interview.guide.modules.voiceinterview.turn;

/**
 * A voice reply turn's externally observable lifecycle.
 */
public enum VoiceTurnPhase {
    THINKING,
    SPEAKING,
    COMPLETED,
    CANCELLED,
    FAILED
}
