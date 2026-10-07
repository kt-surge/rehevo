package interview.guide.experiments;

import com.google.gson.Gson;
import interview.guide.App;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.EvidenceSufficiencyService;
import interview.guide.modules.knowledgebase.service.HybridRetrievalService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseCountService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagCitationValidator;
import interview.guide.modules.knowledgebase.service.RagEvidenceContext;
import interview.guide.modules.knowledgebase.service.RagRoutingDecisionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.DefaultResourceLoader;

/** Actual prompt builders and Registry, fixed public excerpts; no retrieval or session writes. */
public final class RagTermBoundaryStudyMain {
  private static final Gson JSON = new Gson();
  record Evidence(String documentId, String sourceUrl, String sourceVersion, String body) {}
  record Case(String id, String question, List<Evidence> contexts) {}
  record Call(String caseId, String arm) {}
  record Study(String payloadOrigin, List<Case> cases, List<Call> schedule) {}

  public static void main(String[] args) throws Exception {
    var input = Path.of(System.getProperty("term.inputs"));
    var output = Path.of(System.getProperty("term.output"));
    var study = JSON.fromJson(Files.readString(input, StandardCharsets.UTF_8), Study.class);
    int offset = Integer.getInteger("term.offset", 0);
    int limit = Integer.getInteger("term.limit", 4);
    if (!study.payloadOrigin().equals("frozen-public-term-boundary-dev-v1") || study.cases().size() != 6
        || study.schedule().size() != 12 || limit < 1 || limit > 12 || offset < 0
        || offset + limit > 12 || Files.exists(output.resolve("events.jsonl"))) {
      throw new IllegalArgumentException("Only bounded prepared public RAG preflight is supported");
    }
    var systemMethod = KnowledgeBaseQueryService.class.getDeclaredMethod("buildSystemPrompt");
    var userMethod = KnowledgeBaseQueryService.class.getDeclaredMethod("buildUserPrompt", String.class, String.class);
    systemMethod.setAccessible(true);
    userMethod.setAccessible(true);
    try (var events = Files.newBufferedWriter(output.resolve("events.jsonl"), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW); var context = SpringApplication.run(App.class, args)) {
      var registry = context.getBean(LlmProviderRegistry.class);
      var active = context.getBean(KnowledgeBaseQueryProperties.class);
      var properties = new KnowledgeBaseQueryProperties();
      properties.setSystemPromptPath(active.getSystemPromptPath());
      properties.setUserPromptPath(active.getUserPromptPath());
      properties.setRewritePromptPath(active.getRewritePromptPath());
      for (int index = offset; index < offset + limit; index++) {
        Call call = study.schedule().get(index);
        if (!List.of("current", "grounded").contains(call.arm())) throw new IllegalArgumentException("Unknown arm");
        Case item = study.cases().stream().filter(c -> c.id().equals(call.caseId())).findFirst().orElseThrow();
        properties.getCitation().setEnabled(true);
        var service = new KnowledgeBaseQueryService(registry, context.getBean(HybridRetrievalService.class),
            context.getBean(KnowledgeBaseListService.class), context.getBean(KnowledgeBaseCountService.class),
            context.getBean(KnowledgeBaseRepository.class), context.getBean(ApplicationMetrics.class),
            context.getBean(EvidenceSufficiencyService.class), context.getBean(RagRoutingDecisionService.class),
            properties, context.getBean(DocumentChunkingProperties.class), new DefaultResourceLoader());
        var documents = item.contexts().stream().map(e -> new Document(e.body())).toList();
        var supplied = RagEvidenceContext.from(documents, true);
        String system = (String) systemMethod.invoke(service);
        String oldRule = "使用准确的专业术语，必要时进行解释";
        String newRule = "只使用知识库支持的专业术语；知识库未提供释义时不自行补充解释";
        if (system.indexOf(oldRule) < 0 || system.indexOf(oldRule) != system.lastIndexOf(oldRule)) {
          throw new IllegalArgumentException("Expected exactly one current terminology rule");
        }
        if (call.arm().equals("grounded")) system = system.replace(oldRule, newRule);
        String user = (String) userMethod.invoke(service, supplied.text(), item.question());
        String promptName = String.format("%02d-prompt.json", index);
        Files.writeString(output.resolve(promptName), JSON.toJson(Map.of("system", system, "user", user,
            "evidenceIds", supplied.evidenceIds(), "model", "qwen3.8-flash", "temperature", 0.2,
            "maxCompletionTokens", 480, "enableThinking", false)), StandardOpenOption.CREATE_NEW);
        events.write(JSON.toJson(Map.of("kind", "begin", "at", Instant.now().toString(), "index", index,
            "caseId", call.caseId(), "arm", call.arm(), "prompt", promptName)) + "\n");
        events.flush();
        long start = System.nanoTime();
        try {
          var response = registry.getChatClientOrDefault(null, LlmProviderRegistry.ToolAccess.NONE).prompt().system(system).user(user)
              .options(OpenAiChatOptions.builder().model("qwen3.8-flash").temperature(0.2)
                  .maxCompletionTokens(480).toolChoice("none").extraBody(Map.of("enable_thinking", false,
                      "preserve_thinking", false))).call().chatResponse();
          String answer = response.getResult().getOutput().getText();
          var usage = response.getMetadata().getUsage();
          var result = Map.of("kind", "result", "index", index, "caseId", call.caseId(), "arm", call.arm(),
              "answer", answer, "citationValidation", RagCitationValidator.check(answer, supplied.evidenceIds()),
              "usage", Map.of("inputTokens", usage.getPromptTokens(), "outputTokens", usage.getCompletionTokens(),
                  "totalTokens", usage.getTotalTokens()), "responseModel", response.getMetadata().getModel(),
              "elapsedMs", (System.nanoTime() - start) / 1_000_000.0);
          events.write(JSON.toJson(result) + "\n");
          events.flush();
          System.out.println("Completed " + index + " " + call.caseId() + " " + call.arm());
        } catch (Exception exception) {
          events.write(JSON.toJson(Map.of("kind", "failure", "index", index, "caseId", call.caseId(),
              "arm", call.arm(), "exceptionType", exception.getClass().getName(),
              "usage", "unknown", "elapsedMs", (System.nanoTime() - start) / 1_000_000.0)) + "\n");
          events.flush();
          throw exception;
        }
      }
    }
  }
}
