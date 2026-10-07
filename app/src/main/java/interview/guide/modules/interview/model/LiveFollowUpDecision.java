package interview.guide.modules.interview.model;

/**
 * LIVE 阶段对预生成追问的轻量观察结果。
 *
 * <p>默认只观测，不改变题目序列；因此 {@code ADVANCE} 表示“当前回答已覆盖足够关键点，
 * 可在后续受控实验中考虑换主体题”，而不是已经跳过追问。</p>
 */
public record LiveFollowUpDecision(
    Action action,
    Integer followUpQuestionIndex,
    int matchedKeyPoints,
    int totalKeyPoints,
    boolean followUpRelevant,
    boolean duplicateQuestion
) {
    public enum Action {
        DEEPEN,
        ADVANCE,
        NOT_APPLICABLE
    }
}
