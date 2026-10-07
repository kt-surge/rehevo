package interview.guide.modules.interview.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    private int followUpCount = 1;
    private LiveFollowUp liveFollowUp = new LiveFollowUp();
    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";

    @Data
    public static class LiveFollowUp {
        /** 仅返回建议和指标，不改变题目序列；强制跳题须先通过固定集验收。 */
        private Mode mode = Mode.OBSERVE;
        private int minimumAnswerCharacters = 40;
        private int minimumKeyPointHits = 1;
    }

    public enum Mode {
        OBSERVE
    }
}
