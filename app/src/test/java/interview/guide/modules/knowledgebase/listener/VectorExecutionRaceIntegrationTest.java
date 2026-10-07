package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Identical A/B requirements run two actual consumers; no real model or recovery latency claim. */
@DisplayName("真实双消费者同版本执行竞争对照")
class VectorExecutionRaceIntegrationTest extends VectorizeFaultIntegrationTest {
  private VectorizeStreamConsumer second;
  private final CountDownLatch releaseFirst = new CountDownLatch(1);
  private final CountDownLatch releaseSecond = new CountDownLatch(1);

  @AfterEach
  void releaseWorkers() {
    releaseFirst.countDown();
    releaseSecond.countDown();
    if (second != null) { second.shutdown(); }
  }

  @Test
  @DisplayName("有效执行期间同版本第二通知不得重复调用 Embedding")
  void executionDuplicateNotificationCannotComputeConcurrently() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      int current = calls.incrementAndGet();
      if (current == 1) {
        firstEntered.countDown();
        assertThat(releaseFirst.await(25, TimeUnit.SECONDS)).isTrue();
      }
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    String content = "controlled execution duplicate fixture: only one worker owns this accepted request.";
    acceptAndStart(content);
    assertThat(firstEntered.await(12, TimeUnit.SECONDS)).isTrue();
    enqueue(content);
    startSecond();
    // lag is unread messages, not Pending. Confirm the second physical notification was ACKed
    // while the first execution remains held; both A-completed and B-skipped paths must reach it.
    await(() -> redis.streamGroupMetrics(stream(), group()).backlog() == 0
        && redis.streamGroupMetrics(stream(), group()).pending() == 1);
    int callsDuringFirstExecution = calls.get();
    releaseFirst.countDown();
    await(() -> redis.streamGroupMetrics(stream(), group()).backlog() == 0
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    Map<String, Object> observation = Map.of("twoActualConsumers", true,
        "callsDuringFirstExecution", callsDuringFirstExecution, "finalFixtureCalls", calls.get(),
        "pending", redis.streamGroupMetrics(stream(), group()).pending(), "status", status());
    writeObservation("duplicate", observation);
    assertThat(callsDuringFirstExecution).as("活跃执行权期间不得重复计算").isEqualTo(1);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(status()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("租约到期后的旧成功不得抢先完成并阻止新执行提交")
  void executionExpiredOldSuccessCannotWinBeforeNewCompletion() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    CountDownLatch firstReturned = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      List<Document> documents = invocation.getArgument(0);
      int current = calls.incrementAndGet();
      if (current == 1) {
        firstEntered.countDown();
        assertThat(releaseFirst.await(25, TimeUnit.SECONDS)).isTrue();
      } else {
        secondEntered.countDown();
        assertThat(releaseSecond.await(25, TimeUnit.SECONDS)).isTrue();
      }
      for (Document document : documents) { document.getMetadata().put("fixture_worker", current); }
      writeFixtureVectors(documents);
      if (current == 1) { firstReturned.countDown(); }
      return null;
    }).when(store).add(anyList());
    String content = "controlled expired execution fixture: current execution must submit its own result.";
    acceptAndStart(content);
    assertThat(firstEntered.await(12, TimeUnit.SECONDS)).isTrue();
    boolean leaseColumn = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
        + "AND table_name='kb_vector_tasks' AND column_name='execution_lease_until'", Integer.class) == 1;
    if (leaseColumn) {
      jdbc.update("UPDATE kb_vector_tasks SET execution_lease_until=clock_timestamp()-interval '1 second' WHERE generation=?", generation);
    }
    enqueue(content);
    startSecond();
    assertThat(secondEntered.await(12, TimeUnit.SECONDS)).isTrue();
    releaseFirst.countDown();
    assertThat(firstReturned.await(12, TimeUnit.SECONDS)).isTrue();
    await(() -> redis.streamGroupMetrics(stream(), group()).pending() == 1);
    String statusBeforeNewCompletion = status();
    releaseSecond.countDown();
    await(() -> redis.streamGroupMetrics(stream(), group()).backlog() == 0
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    List<String> winningWorkers = jdbc.queryForList("SELECT metadata->>'fixture_worker' FROM vector_store "
        + "WHERE metadata->>'kb_id'=?", String.class, kbId.toString());
    writeObservation("expired-success", Map.of("leaseColumnPresent", leaseColumn,
        "leaseTimeAdvancedByFixture", leaseColumn, "oldStatusBeforeNewCompletion", statusBeforeNewCompletion,
        "winningWorkers", winningWorkers, "finalFixtureCalls", calls.get(), "status", status()));
    assertThat(statusBeforeNewCompletion).as("过期执行不得完成父文档").isEqualTo("PROCESSING");
    assertThat(winningWorkers).containsExactly("2");
    assertThat(status()).isEqualTo("COMPLETED");
  }

  private void acceptAndStart(String content) {
    generation = tasks.begin(kbId, tasks.prepareInput(content));
    enqueue(content);
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
  }

  private void enqueue(String content) {
    redis.streamAdd(stream(), Map.of("kbId", kbId.toString(), "content", content,
        "generation", generation, "retryCount", "0"));
  }

  private void startSecond() {
    second = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    second.init();
  }

  private void writeObservation(String name, Map<String, Object> value) throws Exception {
    Path directory = Path.of(System.getProperty("rehevo.fault.artifacts")).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Files.write(directory.resolve(name + ".json"), mapper.writeValueAsBytes(value));
    System.out.println("EXECUTION_RACE " + name + " " + value);
  }

  private String status() { return jdbc.queryForObject("SELECT vector_status FROM knowledge_bases WHERE id=?", String.class, kbId); }
  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }
}
