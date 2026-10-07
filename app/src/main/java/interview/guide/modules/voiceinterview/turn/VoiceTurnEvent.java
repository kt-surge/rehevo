package interview.guide.modules.voiceinterview.turn;

/**
 * Metadata attached to an outbound message belonging to a voice reply turn.
 */
public record VoiceTurnEvent(
    String turnId,
    long sequence,
    String eventId,
    VoiceTurnPhase phase
) {
}
