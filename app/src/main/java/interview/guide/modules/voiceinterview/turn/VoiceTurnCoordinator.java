package interview.guide.modules.voiceinterview.turn;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per WebSocket-session reply-turn coordinator.
 *
 * <p>A newly created turn supersedes the previous active turn. Async LLM/TTS
 * callbacks must check {@link #isActive(VoiceTurnToken)} before sending a
 * payload, so cancellation and a newer turn cannot leak stale output.</p>
 */
public final class VoiceTurnCoordinator {

    private final AtomicLong turnOrdinal = new AtomicLong();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicReference<TurnState> active = new AtomicReference<>();

    public VoiceTurnToken begin() {
        long ordinal = turnOrdinal.incrementAndGet();
        VoiceTurnToken token = new VoiceTurnToken("turn-" + ordinal, ordinal);
        active.set(new TurnState(token, VoiceTurnPhase.THINKING));
        return token;
    }

    public boolean isActive(VoiceTurnToken token) {
        TurnState current = active.get();
        return current != null && current.token().equals(token)
            && (current.phase() == VoiceTurnPhase.THINKING || current.phase() == VoiceTurnPhase.SPEAKING);
    }

    public Optional<VoiceTurnEvent> markSpeaking(VoiceTurnToken token) {
        return transition(token, VoiceTurnPhase.SPEAKING);
    }

    public Optional<VoiceTurnEvent> complete(VoiceTurnToken token) {
        return transition(token, VoiceTurnPhase.COMPLETED);
    }

    public Optional<VoiceTurnEvent> fail(VoiceTurnToken token) {
        return transition(token, VoiceTurnPhase.FAILED);
    }

    public Optional<VoiceTurnEvent> cancelActive() {
        while (true) {
            TurnState current = active.get();
            if (current == null || !isLive(current.phase())) {
                return Optional.empty();
            }
            TurnState cancelled = new TurnState(current.token(), VoiceTurnPhase.CANCELLED);
            if (active.compareAndSet(current, cancelled)) {
                return Optional.of(eventFor(cancelled));
            }
        }
    }

    public Optional<VoiceTurnEvent> nextEvent(VoiceTurnToken token) {
        if (!isActive(token)) {
            return Optional.empty();
        }
        TurnState current = active.get();
        return Optional.of(eventFor(current));
    }

    public boolean isCancelled(VoiceTurnToken token) {
        TurnState current = active.get();
        return current != null && current.token().equals(token) && current.phase() == VoiceTurnPhase.CANCELLED;
    }

    private Optional<VoiceTurnEvent> transition(VoiceTurnToken token, VoiceTurnPhase target) {
        while (true) {
            TurnState current = active.get();
            if (current == null || !current.token().equals(token) || !isLive(current.phase())) {
                return Optional.empty();
            }
            TurnState next = new TurnState(token, target);
            if (active.compareAndSet(current, next)) {
                return Optional.of(eventFor(next));
            }
        }
    }

    private VoiceTurnEvent eventFor(TurnState state) {
        long sequence = eventSequence.incrementAndGet();
        return new VoiceTurnEvent(
            state.token().turnId(),
            sequence,
            state.token().turnId() + "-event-" + sequence,
            state.phase()
        );
    }

    private boolean isLive(VoiceTurnPhase phase) {
        return phase == VoiceTurnPhase.THINKING || phase == VoiceTurnPhase.SPEAKING;
    }

    private record TurnState(VoiceTurnToken token, VoiceTurnPhase phase) {
    }
}
