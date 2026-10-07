package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 使用真实冻结问题/候选测选择 CPU；无数据库、Embedding、生成或 API 调用。 */
public class IdentifierSelectionComponentBenchmark {
  private static final JTokkitTokenCountEstimator ESTIMATOR = new JTokkitTokenCountEstimator();
  private static volatile int sink;

  record Input(String id, String question, List<Document> candidates) {}

  private static int run(Input input, boolean identifier) {
    if (identifier) {
      var result = IdentifierEvidenceSelector.select(input.question(), input.candidates(),
          8, 6000, ESTIMATOR::estimate);
      return result.tokenEstimate();
    }
    List<Document> selected = new ArrayList<>();
    int used = 0;
    for (Document candidate : input.candidates()) {
      if (selected.size() >= 8) {
        break;
      }
      int tokens = ESTIMATOR.estimate(candidate.getText());
      if (used + tokens <= 6000) {
        selected.add(candidate);
        used += tokens;
      }
    }
    // Original HYBRID performs this trace-accounting pass after budget selection.
    return selected.stream().mapToInt(document -> ESTIMATOR.estimate(document.getText())).sum();
  }

  private static double percentile(List<Double> values, double fraction) {
    List<Double> sorted = values.stream().sorted().toList();
    return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1);
  }

  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    List<Input> inputs = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      JsonNode row = mapper.readTree(line);
      List<Document> candidates = new ArrayList<>();
      row.get("candidates").forEach(candidate -> candidates.add(Document.builder()
          .id(candidate.get("id").asText()).text(candidate.get("text").asText()).score(0.8).build()));
      inputs.add(new Input(row.get("id").asText(), row.get("question").asText(), candidates));
    }
    for (int warmup = 0; warmup < 3; warmup++) {
      for (Input input : inputs) {
        sink = run(input, false);
        sink = run(input, true);
      }
    }
    List<Map<String, Object>> records = new ArrayList<>();
    Random random = new Random(61004);
    for (int round = 0; round < 8; round++) {
      Collections.shuffle(inputs, random);
      for (Input input : inputs) {
        boolean first = random.nextBoolean();
        for (boolean identifier : List.of(first, !first)) {
          long start = System.nanoTime();
          sink = run(input, identifier);
          double elapsed = (System.nanoTime() - start) / 1_000_000.0;
          records.add(Map.of("caseId", input.id(), "round", round,
              "label", identifier ? "B" : "A", "elapsedMs", elapsed));
        }
      }
      System.out.println("Completed component round " + (round + 1));
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "same actual B candidates; randomized paired local CPU only, no API or provider calls");
    report.put("qualitySamplesAdded", 0);
    report.put("uniqueInputs", inputs.size());
    report.put("warmupPasses", 3);
    report.put("measuredPasses", 8);
    for (String label : List.of("A", "B")) {
      List<Double> elapsed = records.stream().filter(row -> row.get("label").equals(label))
          .map(row -> (Double) row.get("elapsedMs")).toList();
      report.put(label, Map.of("samples", elapsed.size(), "p50Ms", percentile(elapsed, 0.5),
          "p95Ms", percentile(elapsed, 0.95)));
    }
    report.put("records", records);
    Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        + "\n", StandardCharsets.UTF_8);
    System.out.println("Saved component timing; no end-to-end latency or quality claim.");
  }
}
