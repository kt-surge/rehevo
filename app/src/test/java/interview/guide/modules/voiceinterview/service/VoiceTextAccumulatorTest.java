package interview.guide.modules.voiceinterview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("语音流文本预算和完整句边界")
class VoiceTextAccumulatorTest {

  @Test
  @DisplayName("实际长追问超过预算时不播报被切掉问题条件的省略号")
  void doesNotSpeakCutOffQuestion() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);
    text.append("你刚才提到的 WebSocket 和语音合成似乎偏离了 Redis Stream 的上下文。请回到上一个问题：当消费者处理完新版本后，Redis Stream 中残留的旧版本未确认消息（Pending），你是如何清理或确保它们不会干扰最终一致性？");
    assertThat(text.finish()).doesNotContain("…");
    assertThat(sentences).allMatch(sentence -> sentence.endsWith("？") || sentence.endsWith("。"));
  }

  @Test
  @DisplayName("一个 Token 的未完成尾巴不会随前一句提前播出")
  void retainsUnfinishedTail() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);

    text.append("第一句。第二句的");
    assertThat(sentences).isEmpty();
    text.append("尾巴？第三句");
    assertThat(sentences).containsExactly("第一句。", "第二句的尾巴？");

    assertThat(text.finish()).isEqualTo("第一句。第二句的尾巴？");
    assertThat(sentences).containsExactly("第一句。", "第二句的尾巴？");
  }

  @Test
  @DisplayName("Token 边界上的小数和版本号不会被切开")
  void waitsForAmbiguousPeriod() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);

    text.append("使用 qwen3.");
    assertThat(sentences).isEmpty();
    text.append("7 和 Java 21，配置 server.");
    assertThat(sentences).isEmpty();
    text.append("port 为 8080。下一题？");

    assertThat(sentences).containsExactly(
        "使用 qwen3.7 和 Java 21，配置 server.port 为 8080。", "下一题？");
    assertThat(String.join("", sentences)).isEqualTo(text.finish());
  }

  @Test
  @DisplayName("英文句号确认边界后及时发句并保留句间空格")
  void confirmsEnglishSentenceBoundary() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);

    text.append("Explain it.");
    assertThat(sentences).isEmpty();
    text.append(" Next question?");
    assertThat(sentences).containsExactly("Explain it.", " Next question?");
    text.finish();

    assertThat(String.join("", sentences)).isEqualTo("Explain it. Next question?");
  }

  @Test
  @DisplayName("超预算问题不发残缺语音，保留有限草稿用于一次压缩")
  void truncatesBeforeEmittingOutOfBudgetText() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(12, sentences::add);

    text.append("先答。然后");
    text.append("说明这个特别长的技术问题。");
    text.append("不应该被接收的补充。");

    String result = text.finish();
    assertThat(result).isEmpty();
    assertThat(sentences).isEmpty();
    assertThat(text.repairSource()).contains("然后说明这个特别长的技术问题。");
  }

  @Test
  @DisplayName("恰好预算的问题立即可播，后续输出不会替换或重播")
  void holdsBudgetBoundaryCharacter() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(6, sentences::add);

    text.append("一二三四五？");
    assertThat(sentences).containsExactly("一二三四五？");
    assertThat(text.preview()).isEqualTo("一二三四五？");
    text.append("补充");
    assertThat(text.finish()).isEqualTo("一二三四五？");
    assertThat(sentences).containsExactly("一二三四五？");
  }

  @Test
  @DisplayName("恰好预算长度的完整回复在结束时不丢最后一个字符")
  void keepsExactBudgetResponse() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(6, sentences::add);

    text.append("一二三四五？");

    assertThat(text.finish()).isEqualTo("一二三四五？");
    assertThat(sentences).containsExactly("一二三四五？");
  }

  @Test
  @DisplayName("Markdown 清理和任意二段 Token 切分不改变最终播报内容")
  void preservesContentAcrossTokenPartitions() {
    String input = "**第一句**。\n- Java 21 与 `server.port` 的关系？最后说明 2.0 版本";
    String expected = "第一句。 Java 21 与 server.port 的关系？";
    for (int split = 1; split < input.length(); split++) {
      List<String> sentences = new ArrayList<>();
      VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);
      text.append(input.substring(0, split));
      text.append(input.substring(split));

      assertThat(text.finish()).as("Token 切分位置 %s", split).isEqualTo(expected);
      assertThat(String.join("", sentences)).as("播报拼接位置 %s", split).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("超预算不播的结果与 Token 切分方式无关")
  void budgetIsIndependentOfPartition() {
    String input = "简述。请解释 Spring AI 2.0 的调用流程与事务边界。";
    for (int split = 1; split < input.length(); split++) {
      List<String> sentences = new ArrayList<>();
      VoiceTextAccumulator text = new VoiceTextAccumulator(18, sentences::add);
      text.append(input.substring(0, split));
      text.append(input.substring(split));
      assertThat(text.finish()).isEmpty();
      assertThat(String.join("", sentences)).isEqualTo(text.finish());
    }
  }

  @Test
  @DisplayName("草稿预览不会留下不完整的 Unicode 代理对")
  void keepsSurrogatePairIntact() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(5, sentences::add);
    text.append("一二三四😀后文");

    assertThat(text.preview()).isEqualTo("一二三四");
    assertThat(text.finish()).isEmpty();
    assertThat(sentences).isEmpty();
  }

  @Test
  @DisplayName("空流不能把无考点占位语当完整问题，重复结束不重播")
  void finishesOnlyOnceWithEmptyFallback() {
    List<String> sentences = new ArrayList<>();
    VoiceTextAccumulator text = new VoiceTextAccumulator(120, sentences::add);
    text.append(null);
    text.append(" ");

    assertThat(text.finish()).isEmpty();
    text.finish();
    text.append("结束后的数据");
    assertThat(sentences).isEmpty();
    assertThat(text.preview()).isEmpty();
  }
}
