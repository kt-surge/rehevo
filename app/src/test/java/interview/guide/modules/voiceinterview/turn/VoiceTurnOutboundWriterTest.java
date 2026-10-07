package interview.guide.modules.voiceinterview.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("语音 Turn 出站写入器")
class VoiceTurnOutboundWriterTest {

    @Test
    @DisplayName("并发回调的序号分配和实际写出顺序保持一致")
    void serializesSequenceAssignmentAndWriteOperationTogether() throws InterruptedException {
        VoiceTurnOutboundWriter writer = new VoiceTurnOutboundWriter();
        List<VoiceTurnEvent> emitted = new CopyOnWriteArrayList<>();
        List<Thread> callbacks = new ArrayList<>();

        for (int index = 0; index < 40; index++) {
            callbacks.add(Thread.startVirtualThread(() -> writer.write(
                new VoiceTurnEvent("turn-1", 0, "ignored", VoiceTurnPhase.SPEAKING),
                emitted::add
            )));
        }
        for (Thread callback : callbacks) {
            callback.join();
        }

        assertThat(emitted).hasSize(40);
        assertThat(emitted).extracting(VoiceTurnEvent::sequence)
            .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList());
        assertThat(emitted).allSatisfy(event -> {
            assertThat(event.eventId()).isEqualTo("turn-1-event-" + event.sequence());
            assertThat(event.phase()).isEqualTo(VoiceTurnPhase.SPEAKING);
        });
    }
}
