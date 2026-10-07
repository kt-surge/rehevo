package interview.guide.modules.voiceinterview.turn;

/**
 * Identifies one server generated reply within a WebSocket session.
 */
public record VoiceTurnToken(String turnId, long ordinal) {
}
