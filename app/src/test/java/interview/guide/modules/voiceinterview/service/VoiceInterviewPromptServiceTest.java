package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

@DisplayName("语音面试提示词服务测试")
class VoiceInterviewPromptServiceTest {

  private final PromptSanitizer promptSanitizer = mock(PromptSanitizer.class);
  private final InterviewSkillService skillService = mock(InterviewSkillService.class);
  private final VoiceInterviewProperties properties = new VoiceInterviewProperties();
  private final VoiceInterviewPromptService service =
      new VoiceInterviewPromptService(promptSanitizer, skillService, properties);

  @Test
  @DisplayName("技能与简历上下文被安全写入系统提示词")
  void generatePromptWithSkillAndResume() {
    when(promptSanitizer.sanitize("原始简历")).thenReturn("安全简历");
    when(promptSanitizer.wrapWithDelimiters("resume", "安全简历"))
        .thenReturn("<resume>安全简历</resume>");

    String prompt = service.generateSystemPromptWithContext("java-backend", "原始简历");

    assertThat(prompt)
        .contains("java-backend")
        .contains("每轮只问 1 个主问题")
        .contains("<resume>安全简历</resume>")
        .endsWith(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
  }

  @Test
  @DisplayName("无技能和简历时仍保留语音约束与安全指令")
  void generateMinimalPrompt() {
    String prompt = service.generateSystemPromptWithContext(null, null);

    assertThat(prompt)
        .contains("语音面试输出约束")
        .doesNotContain("候选人简历内容")
        .endsWith(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
  }

  @Test
  @DisplayName("启用预加载后完整岗位规则直接进入提示词且不要求再调用工具")
  void preloadsSelectedSkillPersona() {
    properties.setSkillPreloadEnabled(true);
    when(skillService.getSkill("java-backend")).thenReturn(new InterviewSkillService.SkillDTO(
        "java-backend", "Java 后端开发", "desc", List.of(), true, null,
        "追问事务提交后确认丢失与重复执行。", null));

    assertThat(service.generateSystemPromptWithContext("java-backend", null))
        .contains("Java 后端开发", "追问事务提交后确认丢失与重复执行。", "未提供的参考资料正文不可声称已查阅")
        .doesNotContain("请调用 Skill 工具")
        .endsWith(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
    assertThat(service.isSkillPreloaded("java-backend")).isTrue();
    assertThat(service.isSkillPreloaded("custom")).isFalse();
  }
}
