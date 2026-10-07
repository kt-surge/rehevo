package interview.guide.common.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.LlmProviderProperties;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.SignalType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 实际 Registry + SDK 对本地 HTTP SSE；观察对端 EOF，不调用真实模型。 */
public class SdkWireCancellationProbe {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long OBSERVE_MS = 1800;

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    var results = new ArrayList<Map<String, Object>>();
    for (String name : List.of("normal", "mid-cancel", "before-first", "before-headers",
        "deadline", "malformed-response")) {
      results.add(runCase(name));
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.resolve("wire-results.json").toFile(),
          Map.of("dependencyArm", args[1], "actualModelCalls", 0, "cases", results,
              "scope", "Local socket EOF correctness; not supplier billing or production latency."));
    }
    System.out.println(MAPPER.writeValueAsString(Map.of("arm", args[1], "cases", results)));
  }

  private static Map<String, Object> runCase(String name) throws Exception {
    try (var fixture = new WireFixture(name)) {
      var properties = new LlmProviderProperties();
      var provider = new LlmProviderProperties.ProviderConfig();
      provider.setBaseUrl("http://127.0.0.1:" + fixture.port());
      provider.setApiKey("controlled-local-fixture-key");
      provider.setModel("controlled-model");
      properties.setProviders(Map.of("wire", provider));
      properties.setDefaultProvider("wire");
      var registry = new LlmProviderRegistry(properties,
          DefaultToolCallingManager.builder().build(), null, null);
      var responses = new ArrayList<ChatResponse>();
      var signal = new AtomicReference<String>();
      var failure = new AtomicReference<String>();
      var first = new CountDownLatch(1);
      var terminated = new CountDownLatch(1);
      var cancelAt = new AtomicLong();
      var subscriber = new BaseSubscriber<ChatResponse>() {
        @Override
        protected void hookOnNext(ChatResponse response) {
          synchronized (responses) { responses.add(response); }
          if (response.getResult() != null && !response.getResult().getOutput().getText().isEmpty()) {
            first.countDown();
          }
        }
        @Override
        protected void hookOnError(Throwable throwable) {
          failure.set(throwable.getClass().getSimpleName());
        }
        @Override
        protected void hookFinally(SignalType type) {
          signal.set(type.name());
          terminated.countDown();
        }
      };
      var stream = registry.getChatClientOrDefault(null, LlmProviderRegistry.ToolAccess.NONE)
          .prompt("controlled public SSE fixture").stream().chatResponse();
      if (name.equals("deadline")) {
        stream = stream.timeout(Duration.ofMillis(350));
      }
      stream.subscribe(subscriber);
      if (!fixture.requestReceived.await(6, TimeUnit.SECONDS)) {
        throw new AssertionError("No local request: " + name);
      }
      if (name.equals("mid-cancel")) {
        if (!first.await(6, TimeUnit.SECONDS)) { throw new AssertionError("No first content"); }
        cancelAt.set(System.nanoTime());
        subscriber.dispose();
        subscriber.dispose();
      } else if (name.equals("before-first") || name.equals("before-headers")) {
        if (name.equals("before-first") && !fixture.headersSent.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("No headers");
        }
        cancelAt.set(System.nanoTime());
        subscriber.dispose();
      }
      if (!terminated.await(6, TimeUnit.SECONDS)) { throw new AssertionError("No client terminal"); }
      if (cancelAt.get() == 0 && !name.equals("normal")) {
        cancelAt.set(System.nanoTime());
      }
      boolean eof = fixture.peerClosed.await(OBSERVE_MS, TimeUnit.MILLISECONDS);
      var result = new LinkedHashMap<String, Object>();
      result.put("case", name);
      result.put("terminal", signal.get());
      result.put("errorType", failure.get());
      result.put("peerCloseObservedBeforeFixtureCleanup", eof);
      result.put("peerCloseKind", fixture.closeKind.get());
      result.put("closeAfterCancelMs", cancelAt.get() == 0 || !eof ? null
          : (fixture.closedAt.get() - cancelAt.get()) / 1_000_000.0);
      synchronized (responses) {
        result.put("content", responses.stream().filter(r -> r.getResult() != null)
            .map(r -> r.getResult().getOutput().getText()).reduce("", String::concat));
        result.put("lastUsage", responses.isEmpty() ? null
            : responses.getLast().getMetadata().getUsage().getTotalTokens());
        result.put("responses", responses.size());
      }
      if (name.equals("normal")) {
        if (!"ON_COMPLETE".equals(signal.get()) || !"中文😀\\n第二段".equals(result.get("content"))
            || !Integer.valueOf(3).equals(result.get("lastUsage"))) {
          throw new AssertionError("Normal output or usage changed: " + result);
        }
      } else if (name.equals("malformed-response") || name.equals("deadline")) {
        if (!"ON_ERROR".equals(signal.get())) { throw new AssertionError("Expected error: " + result); }
      } else if (!"CANCEL".equals(signal.get())) {
        throw new AssertionError("Expected cancel: " + result);
      }
      return result;
    }
  }

  private static class WireFixture implements AutoCloseable {
    private final ServerSocket server;
    private final Thread writer;
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch headersSent = new CountDownLatch(1);
    private final CountDownLatch peerClosed = new CountDownLatch(1);
    private final AtomicLong closedAt = new AtomicLong();
    private final AtomicReference<String> closeKind = new AtomicReference<>();
    private volatile Socket connection;
    private volatile boolean cleanup;
    private Thread reader;

    WireFixture(String name) throws IOException {
      server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      writer = Thread.ofVirtual().name("controlled-sse-writer").start(() -> serve(name));
    }

    int port() { return server.getLocalPort(); }

    private void observedClose(String kind) {
      if (!cleanup && closedAt.compareAndSet(0, System.nanoTime())) {
        closeKind.set(kind);
        peerClosed.countDown();
      }
    }

    private void serve(String name) {
      try {
        connection = server.accept();
        var input = connection.getInputStream();
        var header = new ByteArrayOutputStream();
        int marker = 0;
        while (marker != 0x0d0a0d0a) {
          int b = input.read();
          if (b < 0) { throw new IOException("Request header EOF"); }
          header.write(b);
          marker = (marker << 8) | b;
          if (header.size() > 65536) { throw new IOException("Oversized fixture header"); }
        }
        String headers = header.toString(StandardCharsets.US_ASCII);
        int length = 0;
        for (String line : headers.split("\r\n")) {
          if (line.toLowerCase().startsWith("content-length:")) {
            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
          }
        }
        if (input.readNBytes(length).length != length) { throw new IOException("Request body EOF"); }
        requestReceived.countDown();
        reader = Thread.ofVirtual().name("controlled-peer-close").start(() -> {
          try {
            if (input.read() == -1) { observedClose("peer-eof"); }
          } catch (IOException e) { observedClose("peer-read-" + e.getClass().getSimpleName()); }
        });
        if (name.equals("before-headers")) { Thread.sleep(500); }
        OutputStream output = connection.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
            + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        headersSent.countDown();
        if (name.equals("before-first") || name.equals("deadline")) { Thread.sleep(800); }
        if (name.equals("malformed-response")) {
          frame(output, "data: {broken\n\n");
        } else {
          chunk(output, "中文😀\\n", null, null);
        }
        if (name.equals("normal")) {
          chunk(output, "第二段", null, null);
          chunk(output, "", "stop", Map.of("prompt_tokens", 1, "completion_tokens", 2, "total_tokens", 3));
          frame(output, "data: [DONE]\n\n");
          output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          output.flush();
          // Client closes because Connection: close; do not close fixture first.
          peerClosed.await(2500, TimeUnit.MILLISECONDS);
        } else {
          for (int i = 0; i < 55 && !cleanup; i++) {
            Thread.sleep(80);
            chunk(output, "迟到", null, null);
          }
        }
      } catch (IOException e) {
        observedClose("peer-write-" + e.getClass().getSimpleName());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void chunk(OutputStream output, String content, String reason,
        Map<String, Integer> usage) throws IOException {
      var choice = new LinkedHashMap<String, Object>();
      choice.put("index", 0);
      choice.put("delta", Map.of("role", "assistant", "content", content));
      choice.put("finish_reason", reason);
      var data = new LinkedHashMap<String, Object>();
      data.put("id", "controlled-completion");
      data.put("object", "chat.completion.chunk");
      data.put("created", 0);
      data.put("model", "controlled-model");
      data.put("choices", List.of(choice));
      data.put("usage", usage);
      frame(output, "data: " + MAPPER.writeValueAsString(data) + "\n\n");
    }

    private void frame(OutputStream output, String data) throws IOException {
      byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
      output.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
      output.write(bytes);
      output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
      output.flush();
    }

    @Override
    public void close() throws Exception {
      cleanup = true;
      if (connection != null) { connection.close(); }
      server.close();
      writer.interrupt();
      writer.join(1500);
      if (reader != null) { reader.join(1500); }
    }
  }
}
