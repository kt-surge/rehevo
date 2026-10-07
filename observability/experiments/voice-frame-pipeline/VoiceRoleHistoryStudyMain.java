package interview.guide.experiments;

import com.google.gson.Gson;
import interview.guide.App;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewPromptService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.SpringApplication;

import java.io.BufferedWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Experiment only: invokes the actual service with fixed public history, without saving sessions. */
public final class VoiceRoleHistoryStudyMain {
  private static final Gson JSON = new Gson();
  private static final AtomicInteger REQUESTS = new AtomicInteger();
  private static volatile Trace active;
  private static BufferedWriter events;
  private static boolean modelStudy;
  private static int requestLimit;
  private static Map<String, ApprovedCase> approvedCases = Map.of();

  public static void main(String[] args) throws Exception {
    Path inputs = Path.of(System.getProperty("rehevo.role.inputs"));
    Path output = Path.of(System.getProperty("rehevo.role.output"));
    if (!Files.isDirectory(output) || Files.exists(output.resolve("events.jsonl"))) {
      throw new IllegalArgumentException("Use a new, prepared output directory");
    }
    Study study = JSON.fromJson(Files.readString(inputs, StandardCharsets.UTF_8), Study.class);
    modelStudy = System.getProperty("rehevo.role.kind", "roles").equals("models");
    requestLimit = modelStudy ? 8 : 24;
    if (modelStudy) {
      Proposal proposal = JSON.fromJson(Files.readString(
          Path.of(System.getProperty("rehevo.role.approvedPlan")), StandardCharsets.UTF_8), Proposal.class);
      if (proposal.maximumRequests() != 8 || study.cases().size() != 4 || study.schedule().size() != 8) {
        throw new IllegalArgumentException("Authorized model study is exactly four contexts and at most eight requests");
      }
      approvedCases = proposal.cases();
    }
    if (study.cases().isEmpty() || study.cases().size() > 12 || study.schedule().size() > 24) {
      throw new IllegalArgumentException("Public preflight must contain 1-12 cases and at most 24 calls");
    }
    int offset = Integer.getInteger("rehevo.role.offset", 0);
    int limit = Integer.getInteger("rehevo.role.limit", 2);
    if (offset < 0 || limit < 1 || offset + limit > study.schedule().size()) {
      throw new IllegalArgumentException("Invalid bounded schedule slice");
    }
    Map<String, Case> cases = new HashMap<>();
    for (Case item : study.cases()) {
      if (cases.put(item.id(), item) != null) throw new IllegalArgumentException("Duplicate case id");
    }
    BufferedWriter eventWriter = Files.newBufferedWriter(output.resolve("events.jsonl"), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
    events = eventWriter;
    try (eventWriter) {
      emit("setup", Map.of("scope", "actual-service fixed-history LLM component quality preflight",
          "inputSha256", sha(Files.readString(inputs, StandardCharsets.UTF_8)),
          "seed", study.seed(), "scheduleOffset", offset, "submittedLimit", limit,
          "tts", "not invoked", "asr", "not invoked", "sessionWrites", "none",
          "studyKind", modelStudy ? "human-authorized actual-service model comparison" : "history roles"));
      try (var context = SpringApplication.run(App.class, args)) {
        LlmProviderRegistry registry = context.getBean(LlmProviderRegistry.class);
        ChatClient original = registry.getPlainChatClient("dashscope");
        Field field = LlmProviderRegistry.class.getDeclaredField("clientCache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ChatClient> cache = (Map<String, ChatClient>) field.get(registry);
        if (cache.get("dashscope:plain") != original) {
          throw new IllegalStateException("Unexpected provider cache identity; no calls made");
        }
        cache.put("dashscope:plain", tracedClient(original));
        try {
          var sanitizer = new SharedBoundarySanitizer(context.getBean(LlmProviderProperties.class));
          if (modelStudy) {
            for (Case item : study.cases()) {
              ApprovedCase approved = approvedCases.get(item.id());
              if (approved == null) throw new IllegalArgumentException("Context outside authorized plan");
              List<ApprovedMessage> messages = approved.messages();
              String user = messages.getLast().content();
              if (!user.startsWith("用户：")) throw new IllegalArgumentException("Unexpected authorized current input");
              sanitizer.boundaries.put("input\u0000" + sanitizer.sanitize(item.input()), user.substring(3));
            }
          }
          var prompt = context.getBean(VoiceInterviewPromptService.class);
          var repo = context.getBean(ResumeRepository.class);
          var effective = context.getBean(VoiceInterviewProperties.class);
          var meters = context.getBean(PrometheusMeterRegistry.class);
          Map<String, DashscopeLlmService> arms = new HashMap<>();
          for (String arm : modelStudy ? List.of("flash", "qwen27b") : List.of("flat", "roles")) {
            var properties = new VoiceInterviewProperties();
            properties.setRoleHistoryEnabled(modelStudy || arm.equals("roles"));
            properties.setAiQuestionMaxChars(effective.getAiQuestionMaxChars());
            properties.setAiQuestionTimeoutSeconds(effective.getAiQuestionTimeoutSeconds());
            properties.setAiStreamPushIntervalMs(effective.getAiStreamPushIntervalMs());
            properties.setAiStreamMinCharsDelta(effective.getAiStreamMinCharsDelta());
            arms.put(arm, new DashscopeLlmService(registry, prompt, repo, properties, sanitizer));
          }
          if (!prompt.isSkillPreloaded("java-backend")) {
            throw new IllegalStateException("Both arms must use the same preloaded public Skill");
          }
          emit("runtime", Map.of("serviceOrigin", DashscopeLlmService.class.getProtectionDomain()
              .getCodeSource().getLocation().toString(), "questionMaxChars", effective.getAiQuestionMaxChars(),
              "questionDeadlineSeconds", effective.getAiQuestionTimeoutSeconds(), "skill", "java-backend"));
          int index = offset;
          for (Call call : study.schedule().subList(offset, offset + limit)) {
            if (REQUESTS.get() >= requestLimit) break;
            Case item = cases.get(call.caseId());
            if (item == null || !arms.containsKey(call.arm())) throw new IllegalArgumentException("Unknown call");
            Trace trace = new Trace(++index, item.id(), call.arm());
            active = trace;
            var session = VoiceInterviewSessionEntity.builder().id(800000L + index)
                .skillId("java-backend").roleType("java-backend").llmProvider("dashscope").build();
            emit("begin", Map.of("trace", trace, "metrics", chatMetrics(meters),
                "history", item.history(), "input", item.input()));
            long start = System.nanoTime();
            int requestStart = REQUESTS.get();
            List<Map<String, Object>> callbacks = new ArrayList<>();
            try {
              var response = arms.get(call.arm()).chatStreamSentences(item.input(),
                  text -> callbacks.add(callback("text", text, start)),
                  text -> callbacks.add(callback("complete_question", text, start)), session, item.history());
              emit("result", Map.of("trace", trace, "success", response.success(),
                  "streaming", response.streaming(), "content", response.content(),
                  "elapsedMs", elapsed(start), "requests", REQUESTS.get() - requestStart,
                  "callbacks", callbacks, "metrics", chatMetrics(meters)));
            } catch (Exception error) {
              emit("failure", Map.of("trace", trace, "exceptionType", error.getClass().getName(),
                  "elapsedMs", elapsed(start), "requests", REQUESTS.get() - requestStart,
                  "callbacks", callbacks, "metrics", chatMetrics(meters)));
            }
            active = null;
            if (REQUESTS.get() == requestStart) {
              throw new IllegalStateException("No request captured; stop to repair the experiment instrumentation");
            }
          }
        } finally {
          cache.put("dashscope:plain", original);
          active = null;
        }
      }
      emit("closed", Map.of("requests", REQUESTS.get(), "springContextClosed", true));
    }
  }

  private static ChatClient tracedClient(ChatClient delegate) {
    return (ChatClient) Proxy.newProxyInstance(ChatClient.class.getClassLoader(),
        new Class<?>[] {ChatClient.class}, (proxy, method, args) -> {
          Object result = invoke(delegate, method, args);
          if (method.getName().equals("prompt") && result instanceof ChatClient.ChatClientRequestSpec request) {
            return tracedRequest(request, active);
          }
          return result;
        });
  }

  private static ChatClient.ChatClientRequestSpec tracedRequest(
      ChatClient.ChatClientRequestSpec delegate, Trace trace) {
    Map<String, Object> supplied = new LinkedHashMap<>();
    supplied.put("trace", trace);
    return (ChatClient.ChatClientRequestSpec) Proxy.newProxyInstance(
        ChatClient.ChatClientRequestSpec.class.getClassLoader(),
        new Class<?>[] {ChatClient.ChatClientRequestSpec.class}, (proxy, method, args) -> {
          if (args != null && args.length == 1) {
            switch (method.getName()) {
              case "system", "user" -> {
                if (args[0] instanceof String text) {
                  supplied.put(method.getName(), text);
                  supplied.put(method.getName() + "Sha256", sha(text));
                }
              }
              case "messages" -> {
                List<?> values = args[0] instanceof List<?> list ? list : Arrays.asList((Object[]) args[0]);
                supplied.put("historyMessages", values.stream().map(value -> {
                  Message message = (Message) value;
                  return Map.of("role", message.getMessageType().getValue(), "text", message.getText());
                }).toList());
              }
              case "options" -> supplied.put("options", safeOptions(args[0], trace));
              default -> { }
            }
          }
          if (method.getName().equals("stream") || method.getName().equals("call")) {
            if (REQUESTS.get() >= requestLimit) {
              throw new IllegalStateException("Approved external request limit reached");
            }
            if (modelStudy) verifyApprovedSupply(supplied, trace);
            supplied.put("requestId", REQUESTS.incrementAndGet());
            supplied.put("path", method.getName());
            emit("request", new LinkedHashMap<>(supplied));
          }
          Object result = invoke(delegate, method, args);
          return result == delegate ? proxy : result;
        });
  }

  private static Map<String, Object> safeOptions(Object builder, Trace trace) throws ReflectiveOperationException {
    if (modelStudy) {
      String model = switch (trace.arm()) {
        case "flash" -> "qwen3.8-flash";
        case "qwen27b" -> "qwen3.8-27b";
        default -> throw new IllegalArgumentException("Model outside the authorized two-model scope");
      };
      ((OpenAiChatOptions.Builder) builder).model(model)
          .extraBody(Map.of("enable_thinking", false, "preserve_thinking", false));
    }
    Object options = builder.getClass().getMethod("build").invoke(builder);
    Map<String, Object> selected = new LinkedHashMap<>();
    for (String name : List.of("getModel", "getMaxCompletionTokens", "getTemperature")) {
      Object value = options.getClass().getMethod(name).invoke(options);
      if (value != null) selected.put(name, value);
    }
    return selected;
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private static void verifyApprovedSupply(Map<String, Object> supplied, Trace trace) {
    ApprovedCase approved = approvedCases.get(trace.caseId());
    List<Map<String, String>> actual = new ArrayList<>();
    actual.add(Map.of("role", "system", "content", (String) supplied.get("system")));
    @SuppressWarnings("unchecked")
    List<Map<String, String>> history = (List<Map<String, String>>) supplied.get("historyMessages");
    if (history != null) {
      for (Map<String, String> item : history) actual.add(Map.of("role", item.get("role"), "content", item.get("text")));
    }
    actual.add(Map.of("role", "user", "content", (String) supplied.get("user")));
    List<Map<String, String>> expected = approved.messages().stream()
        .map(message -> Map.of("role", message.role(), "content", message.content())).toList();
    if (!actual.equals(expected)) {
      throw new IllegalStateException("Actual message differs from reviewed payload; no external call made");
    }
  }

  private static String chatMetrics(PrometheusMeterRegistry registry) {
    return registry.scrape().lines().filter(line -> line.startsWith("gen_ai_client_token_usage_total{")
        && line.contains("gen_ai_operation_name=\"chat\""))
        .reduce("", (a, b) -> a + b + "\n");
  }

  private static Map<String, Object> callback(String kind, String text, long start) {
    return Map.of("kind", kind, "text", text, "elapsedMs", elapsed(start));
  }

  private static double elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000.0;
  }

  private static String sha(String text) throws Exception {
    byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    return java.util.HexFormat.of().formatHex(bytes);
  }

  private static synchronized void emit(String kind, Map<String, ?> data) throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("kind", kind);
    row.put("at", Instant.now().toString());
    row.putAll(data);
    events.write(JSON.toJson(row));
    events.newLine();
    events.flush();
  }

  private static final class SharedBoundarySanitizer extends PromptSanitizer {
    private final Map<String, String> boundaries = new ConcurrentHashMap<>();
    SharedBoundarySanitizer(LlmProviderProperties properties) { super(properties); }
    @Override
    public String wrapWithDelimiters(String label, String text) {
      return boundaries.computeIfAbsent(label + "\u0000" + text, ignored -> super.wrapWithDelimiters(label, text));
    }
  }

  private record Trace(int index, String caseId, String arm) { }
  private record Case(String id, List<String> history, String input) { }
  private record Call(String caseId, String arm) { }
  private record Study(long seed, List<Case> cases, List<Call> schedule) { }
  private record ApprovedMessage(String role, String content) { }
  private record ApprovedCase(List<ApprovedMessage> messages) { }
  private record Proposal(int maximumRequests, Map<String, ApprovedCase> cases) { }
}
