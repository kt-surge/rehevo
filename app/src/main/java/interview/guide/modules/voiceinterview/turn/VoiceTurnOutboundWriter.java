package interview.guide.modules.voiceinterview.turn;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-WebSocket-session outbound writer.
 *
 * <p>It assigns the browser-visible sequence and invokes the actual write while holding the
 * same monitor. This makes the observed write order identical to the assigned order even when
 * LLM and sentence-level TTS callbacks race on different virtual threads.</p>
 */
public final class VoiceTurnOutboundWriter {

    private final AtomicLong sequence = new AtomicLong();
    private final Object writeMonitor = new Object();

    public void write(VoiceTurnEvent event, Consumer<VoiceTurnEvent> writeOperation) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(writeOperation, "writeOperation");
        synchronized (writeMonitor) {
            long nextSequence = sequence.incrementAndGet();
            VoiceTurnEvent serialized = new VoiceTurnEvent(
                event.turnId(),
                nextSequence,
                event.turnId() + "-event-" + nextSequence,
                event.phase()
            );
            writeOperation.accept(serialized);
        }
    }
}
