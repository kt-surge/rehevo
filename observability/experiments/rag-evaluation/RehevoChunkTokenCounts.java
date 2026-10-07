import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** Local token estimates using the already-resolved Spring AI runtime; no provider calls. */
public class RehevoChunkTokenCounts {
  public static void main(String[] args) throws Exception {
    var estimator = new JTokkitTokenCountEstimator();
    var output = new ArrayList<String>();
    for (String row : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      String[] fields = row.split("\t", 2);
      String text = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
      output.add(fields[0] + "\t" + estimator.estimate(text));
    }
    Path target = Path.of(args[1]);
    if (Files.exists(target)) {
      throw new IllegalStateException("Token-count output exists; do not overwrite");
    }
    Files.write(target, output, StandardCharsets.UTF_8);
    System.out.println("Counted " + output.size() + " actual chunks; local estimator, no billed tokens.");
  }
}
