package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
public class VoiceInterviewPromptService {

    private final PromptSanitizer promptSanitizer;
    private final InterviewSkillService skillService;
    private final VoiceInterviewProperties properties;
    private final PromptTemplate preloadedSkillTemplate =
        new PromptTemplate(new ClassPathResource("prompts/voice-skill-preloaded.st"));
    private final PromptTemplate responseConstraintsTemplate =
        new PromptTemplate(new ClassPathResource("prompts/voice-response-constraints.st"));
    private final PromptTemplate questionRepairTemplate =
        new PromptTemplate(new ClassPathResource("prompts/voice-question-repair.st"));

    public VoiceInterviewPromptService(PromptSanitizer promptSanitizer,
                                      InterviewSkillService skillService,
                                      VoiceInterviewProperties properties) {
        this.promptSanitizer = promptSanitizer;
        this.skillService = skillService;
        this.properties = properties;
    }

    public boolean isSkillPreloaded(String skillId) {
      return properties.isSkillPreloadEnabled() && skillId != null && !skillId.isBlank()
          && !InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId);
    }

    public String generateQuestionRepairPrompt(String draft) {
      return questionRepairTemplate.render(Map.of(
          "maxChars", Math.max(80, properties.getAiQuestionMaxChars()),
          "draft", promptSanitizer.wrapWithDelimiters("draft", promptSanitizer.sanitize(draft))));
    }

    private static final String SKILL_TOOL_INSTRUCTION = """
            你是一位 %s 方向的面试官。
            如果尚未加载完整的角色设定，请调用 Skill 工具（command: %s）加载该技能的 SKILL.md。
            工具输出包含完整的面试官角色和出题规则，后续对话应基于该角色进行。
            """;

    public String generateSystemPromptWithContext(String skillId, String resumeText) {
        StringBuilder prompt = new StringBuilder();

        if (isSkillPreloaded(skillId)) {
            var skill = skillService.getSkill(skillId);
            if (skill.persona() == null || skill.persona().isBlank()) {
              throw new BusinessException(ErrorCode.BAD_REQUEST, "面试岗位规则为空");
            }
            prompt.append(preloadedSkillTemplate.render(Map.of(
                "skillId", skill.id(), "skillName", skill.name(), "persona", skill.persona())));
        } else if (skillId != null && !skillId.isBlank()) {
            prompt.append(String.format(SKILL_TOOL_INSTRUCTION, skillId, skillId));
        }

        prompt.append("\n\n").append(responseConstraintsTemplate.render(Map.of(
            "maxChars", Math.max(80, properties.getAiQuestionMaxChars()))));

        if (resumeText != null && !resumeText.isEmpty()) {
            String safeResume = promptSanitizer.sanitize(resumeText);
            prompt.append("\n\n【实时语音面试 - 候选人简历内容】\n")
                .append("你已查阅过候选人简历。首轮仅用一句话说明已查阅，并立即进入首个问题。\n\n")
                .append("【简历解析文本】\n")
                .append(promptSanitizer.wrapWithDelimiters("resume", safeResume));
        }

        prompt.append(PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION);
        return prompt.toString();
    }
}
