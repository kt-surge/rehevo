package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

/** 冻结旧实现与当前实现的差分门；真实冻结候选加独立边界输入，无模型或数据库调用。 */
public class IdentifierScannerParity {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JTokkitTokenCountEstimator TOKENS = new JTokkitTokenCountEstimator();
  private static volatile int sink;

  record Input(String id, String question, List<Document> candidates) {}

  private static Document doc(String id, String text) {
    return Document.builder().id(id).text(text).score(0.8).build();
  }

  private static Map<String, Object> original(Input input, int topK, int budget,
      ToIntFunction<String> estimator) {
    var selection = IdentifierEvidenceSelectorBaseline.select(input.question(), input.candidates(),
        topK, budget, estimator);
    return Map.of("documents", selection.documents(), "anchors", selection.anchors(),
        "reservations", selection.reservations().stream().map(reservation -> List.of(
            reservation.identifier(), reservation.chunkId(), reservation.detailHeading())).toList(),
        "tokens", selection.tokenEstimate());
  }

  private static Map<String, Object> current(Input input, int topK, int budget,
      ToIntFunction<String> estimator) {
    var selection = IdentifierEvidenceSelector.select(input.question(), input.candidates(),
        topK, budget, estimator);
    return Map.of("documents", selection.documents(), "anchors", selection.anchors(),
        "reservations", selection.reservations().stream().map(reservation -> List.of(
            reservation.identifier(), reservation.chunkId(), reservation.detailHeading())).toList(),
        "tokens", selection.tokenEstimate());
  }

  private static void compare(Input input, int topK, int budget, ToIntFunction<String> estimator,
      Path output) throws Exception {
    var before = original(input, topK, budget, estimator);
    var after = current(input, topK, budget, estimator);
    if (!before.equals(after)) {
      write(output.resolve("mismatch.json"), Map.of("input", input, "topK", topK,
          "budget", budget, "baseline", before, "candidate", after));
      throw new IllegalStateException("Scanner parity failed: " + input.id());
    }
  }

  private static String noise(Random random, int count) {
    var out = new StringBuilder();
    String[] pieces = {"x", "_", ".", " ", "\n", "\r", "\t", "中", "é", "😀", "\u00a0", "\u2028"};
    for (int i = 0; i < count; i++) {
      out.append(pieces[random.nextInt(pieces.length)]);
    }
    return out.toString();
  }

  private static List<Input> boundaries() {
    List<Input> inputs = new ArrayList<>();
    // 分别覆盖两段窗口组合、连续标题、非 BMP 字符和所有默认 ASCII 空白。
    for (String unit : List.of("x", "😀")) {
      for (int distance = 0; distance <= 325; distance++) {
        for (String heading : List.of("cancel\n", "cancel\ncancel\n", "cancel\r\n\t\n")) {
          inputs.add(new Input("window-" + inputs.size(), "Work.cancel", List.of(
              doc("summary", "cancel(flag)"), doc("detail", heading
                  + unit.repeat(Math.max(0, distance - 1)) + " cancel\n\t(flag)"))));
        }
      }
    }
    Random random = new Random(61004);
    for (int i = 0; i < 2500; i++) {
      String term = List.of("cancel", "foo-bar", "ABC", "foo_bar").get(i % 4);
      String whitespace = List.of("\n", "\r\n", "\t\f\u000b\n", "\u00a0\n").get(i % 4);
      String heading = noise(random, random.nextInt(25)) + "\n" + term + whitespace;
      if (i % 3 == 0) {
        heading += term + "\n";
      }
      String signature = List.of(" ", "中", "_", "é", "😀", "").get(random.nextInt(6))
          + term + List.of("\n\r\t\f\u000b ", "", "\u00a0").get(random.nextInt(3)) + "(";
      String detail = heading + noise(random, random.nextInt(380)) + signature;
      var candidates = new ArrayList<>(List.of(doc("summary", term + "(flag)"),
          doc("detail", detail), doc(i % 17 == 0 ? "detail" : "other", "cancel\npublic cancel()"),
          doc("random", noise(random, 100) + term)));
      Collections.shuffle(candidates, random);
      String question = i % 13 == 0 ? "Work.cancel Work.ABC Work.foo_bar" : "Work." + term;
      inputs.add(new Input("random-" + i, question, candidates));
    }
    return inputs;
  }

  private static String sha(Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static void write(Path path, Object value) throws Exception {
    Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
        StandardCharsets.UTF_8);
  }

  private static double percentile(List<Double> values, double fraction) {
    var sorted = values.stream().sorted().toList();
    return sorted.get((int) Math.ceil(sorted.size() * fraction) - 1);
  }

  public static void main(String[] args) throws Exception {
    Path inputFile = Path.of(args[0]);
    Path output = Path.of(args[1]);
    Files.createDirectory(output);
    List<Input> actual = new ArrayList<>();
    for (String line : Files.readAllLines(inputFile, StandardCharsets.UTF_8)) {
      JsonNode row = MAPPER.readTree(line);
      List<Document> candidates = new ArrayList<>();
      row.get("candidates").forEach(candidate -> candidates.add(doc(candidate.get("id").asText(),
          candidate.get("text").asText())));
      actual.add(new Input(row.get("id").asText(), row.get("question").asText(), candidates));
    }
    int comparisons = 0;
    for (Input input : actual) {
      compare(input, 8, 6000, TOKENS::estimate, output);
      comparisons++;
      for (int[] limits : List.of(new int[]{1, 20}, new int[]{2, 300}, new int[]{4, 2000}, new int[]{8, 10000})) {
        compare(input, limits[0], limits[1], String::length, output);
        comparisons++;
      }
    }
    List<Input> synthetic = boundaries();
    for (Input input : synthetic) {
      for (int[] limits : List.of(new int[]{2, 2000}, new int[]{4, 30}, new int[]{8, 300})) {
        compare(input, limits[0], limits[1], String::length, output);
        comparisons++;
      }
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "frozen legacy oracle versus new scanner; no retrieval or generation quality samples added");
    report.put("actualInputs", actual.size());
    report.put("syntheticInputs", synthetic.size());
    report.put("parityComparisons", comparisons);
    report.put("parityPassed", true);
    report.put("inputSha256", sha(inputFile));
    report.put("syntheticSeed", 61004);
    report.put("baselineSourceSha256", sha(Path.of(args[2])));
    report.put("candidateSourceSha256", sha(Path.of(args[3])));
    write(output.resolve("parity.json"), report);
    System.out.println("Parity passed: " + comparisons + " comparisons.");

    // 只对原 92 个真实候选测本地 CPU，旧/新策略本身必须先语义一致。
    for (int warmup = 0; warmup < 3; warmup++) {
      for (Input input : actual) {
        sink = (int) original(input, 8, 6000, TOKENS::estimate).get("tokens");
        sink = (int) current(input, 8, 6000, TOKENS::estimate).get("tokens");
      }
    }
    Random random = new Random(61004);
    List<Map<String, Object>> records = new ArrayList<>();
    for (int round = 0; round < 8; round++) {
      Collections.shuffle(actual, random);
      for (Input input : actual) {
        boolean first = random.nextBoolean();
        for (boolean legacy : List.of(first, !first)) {
          long start = System.nanoTime();
          sink = (int) (legacy ? original(input, 8, 6000, TOKENS::estimate)
              : current(input, 8, 6000, TOKENS::estimate)).get("tokens");
          records.add(Map.of("caseId", input.id(), "round", round, "legacy", legacy,
              "elapsedMs", (System.nanoTime() - start) / 1_000_000.0));
        }
      }
    }
    for (boolean legacy : List.of(true, false)) {
      var values = records.stream().filter(row -> row.get("legacy").equals(legacy))
          .map(row -> (Double) row.get("elapsedMs")).toList();
      report.put(legacy ? "legacyCpu" : "candidateCpu", Map.of("observations", values.size(),
          "p50Ms", percentile(values, 0.5), "p95Ms", percentile(values, 0.95)));
    }
    report.put("cpuScope", "same local JVM, 3 warmups, 8 randomized paired passes; not API or end-to-end latency");
    report.put("records", records);
    write(output.resolve("cpu.json"), report);
    System.out.println("Saved local CPU observations; not a product latency claim.");
  }
}
