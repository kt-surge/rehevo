package interview.guide.common.evaluation;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.metrics.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("真实 Registry 与 SDK 评估超时的本地 HTTP 连接释放")
class EvaluationHttpCancellationIntegrationTest {
  @ParameterizedTest(name = "已发送响应头={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("评估截止后对端观察连接关闭，工作线程退出；不代表供应商计费停止")
  void evaluationDeadlineClosesUnaryConnection(boolean headersSent) throws Exception {
    try (var fixture = new WireFixture(headersSent)) {
      var properties = new LlmProviderProperties();
      var provider = new LlmProviderProperties.ProviderConfig();
      provider.setBaseUrl("http://127.0.0.1:" + fixture.port());
      provider.setApiKey("controlled-local-fixture-key");
      provider.setModel("controlled-model");
      properties.setProviders(Map.of("wire", provider));
      properties.setDefaultProvider("wire");
      var llmRegistry = new LlmProviderRegistry(properties,
          DefaultToolCallingManager.builder().build(), null, null);
      var client = llmRegistry.getChatClientOrDefault(null, LlmProviderRegistry.ToolAccess.NONE);
      var executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(1);
      executor.setMaxPoolSize(1);
      executor.setQueueCapacity(1);
      executor.initialize();
      var meters = new SimpleMeterRegistry();
      try {
        var evaluation = new InterviewEvaluationProperties();
        evaluation.setRequestDeadlineSeconds(2);
        evaluation.setFailedItemRetryLimit(0);
        var service = new UnifiedEvaluationService(new StructuredOutputInvoker(new StructuredOutputProperties(), meters),
            new DefaultResourceLoader(), evaluation, new ApplicationMetrics(meters), executor);
        var report = service.evaluate(client, "controlled-wire-deadline",
            List.of(new QaRecord(0, "公开问题", "边界", "公开回答")), "");
        assertThat(fixture.requestReceived.await(500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(report.failedQuestions()).isEqualTo(1);
        assertThat(report.scoredQuestions()).isZero();
        assertThat(fixture.peerClosed.await(1800, TimeUnit.MILLISECONDS))
            .as("取消后客户端主动释放本地 HTTP 连接").isTrue();
        System.out.println("controlled-wire-cancel headersSent=" + headersSent
            + " termination=" + fixture.closeKind.get());
        executor.shutdown();
        assertThat(executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdown();
        meters.close();
      }
    }
  }

  private static final class WireFixture implements AutoCloseable {
    private final ServerSocket server;
    private final Thread writer;
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch peerClosed = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean cleanup = new AtomicBoolean();
    private final AtomicReference<String> closeKind = new AtomicReference<>();
    private volatile Socket connection;
    private Thread reader;

    private WireFixture(boolean headersSent) throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      writer = Thread.ofVirtual().name("controlled-unary-writer").start(() -> serve(headersSent));
    }

    private int port() { return server.getLocalPort(); }

    private void serve(boolean headersSent) {
      try {
        connection = server.accept();
        var input = connection.getInputStream();
        var header = new ByteArrayOutputStream();
        int marker = 0;
        while (marker != 0x0d0a0d0a) {
          int b = input.read();
          if (b < 0) throw new IOException("Request header EOF");
          header.write(b);
          marker = (marker << 8) | b;
          if (header.size() > 65536) throw new IOException("Oversized fixture header");
        }
        int length = 0;
        for (String line : header.toString(StandardCharsets.US_ASCII).split("\r\n")) {
          if (line.toLowerCase().startsWith("content-length:")) {
            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
          }
        }
        if (input.readNBytes(length).length != length) throw new IOException("Request body EOF");
        requestReceived.countDown();
        reader = Thread.ofVirtual().name("controlled-unary-peer").start(() -> {
          try {
            if (input.read() == -1 && !cleanup.get()) {
              closeKind.set("peer-eof");
              peerClosed.countDown();
            }
          } catch (IOException error) {
            if (!cleanup.get()) {
              closeKind.set("peer-read-" + error.getClass().getSimpleName());
              peerClosed.countDown();
            }
          }
        });
        if (headersSent) {
          var output = connection.getOutputStream();
          output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
              + "Content-Length: 1024\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          output.flush();
        }
        release.await(10, TimeUnit.SECONDS);
      } catch (IOException | InterruptedException error) {
        if (!cleanup.get()) throw new AssertionError("Controlled fixture failed", error);
      }
    }

    @Override
    public void close() throws Exception {
      cleanup.set(true);
      release.countDown();
      server.close();
      if (connection != null) connection.close();
      writer.join(2000);
      if (reader != null) reader.join(2000);
    }
  }
}
