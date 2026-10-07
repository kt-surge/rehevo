package interview.guide.modules.voiceinterview.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("语音回复 Turn 状态协调器")
class VoiceTurnCoordinatorTest {

    @Test
    @DisplayName("取消后旧回调不能再取得出站事件")
    void rejectsStaleCallbacksAfterCancellation() {
        VoiceTurnCoordinator coordinator = new VoiceTurnCoordinator();
        VoiceTurnToken turn = coordinator.begin();

        VoiceTurnEvent cancelled = coordinator.cancelActive().orElseThrow();

        assertThat(cancelled.turnId()).isEqualTo(turn.turnId());
        assertThat(cancelled.phase()).isEqualTo(VoiceTurnPhase.CANCELLED);
        assertThat(coordinator.isCancelled(turn)).isTrue();
        assertThat(coordinator.isActive(turn)).isFalse();
        assertThat(coordinator.nextEvent(turn)).isEmpty();
        assertThat(coordinator.complete(turn)).isEmpty();
    }

    @Test
    @DisplayName("新 Turn 会使旧 Turn 的异步回调失效")
    void supersedesPreviousTurn() {
        VoiceTurnCoordinator coordinator = new VoiceTurnCoordinator();
        VoiceTurnToken previous = coordinator.begin();
        VoiceTurnToken current = coordinator.begin();

        assertThat(previous.ordinal()).isLessThan(current.ordinal());
        assertThat(coordinator.isActive(previous)).isFalse();
        assertThat(coordinator.nextEvent(previous)).isEmpty();
        assertThat(coordinator.isActive(current)).isTrue();
    }

    @Test
    @DisplayName("同一会话的事件序号单调递增且阶段可追踪")
    void sequencesEventsAndTracksPhase() {
        VoiceTurnCoordinator coordinator = new VoiceTurnCoordinator();
        VoiceTurnToken turn = coordinator.begin();

        VoiceTurnEvent thinking = coordinator.nextEvent(turn).orElseThrow();
        VoiceTurnEvent speaking = coordinator.markSpeaking(turn).orElseThrow();
        VoiceTurnEvent complete = coordinator.complete(turn).orElseThrow();

        assertThat(thinking.phase()).isEqualTo(VoiceTurnPhase.THINKING);
        assertThat(speaking.phase()).isEqualTo(VoiceTurnPhase.SPEAKING);
        assertThat(complete.phase()).isEqualTo(VoiceTurnPhase.COMPLETED);
        assertThat(thinking.sequence()).isLessThan(speaking.sequence()).isLessThan(complete.sequence());
        assertThat(coordinator.nextEvent(turn)).isEmpty();
    }
}
