package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.common.evaluation.QuestionEvaluationGuide.RubricLevel;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 面试问题生成服务
 * 无简历：单次 Skill 驱动出题
 * 有简历：并行调用（简历题 60% + 方向题 40%）
 */
@Service
public class InterviewQuestionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionService.class);

    private static final String DEFAULT_QUESTION_TYPE = "GENERAL";
    private static final int MAX_FOLLOW_UP_COUNT = 2;
    private static final double RESUME_QUESTION_RATIO = 0.6;

    private static final String GENERIC_MODE_SYSTEM_APPEND = """
        \n\n# 通用面试模式
        本次面试无候选人简历，请出该方向的标准面试题。
        - 禁止出现"你在简历中提到..."、"你在项目中..."等暗示存在简历的表述
        - 问题表述应与简历无关，直接考察该方向的技术能力
        """;

    private static final Map<String, String> DIFFICULTY_DESCRIPTIONS = Map.of(
        "junior", "校招/0-1年经验。考察基础概念和简单应用。",
        "mid", "1-3年经验。考察原理理解和实战经验。",
        "senior", "3年+经验。考察架构设计和深度调优。"
    );

    private static final String[][] GENERIC_FALLBACK_QUESTIONS = {
        {"请描述一个你主导解决的技术难题，你的分析思路是什么？", "GENERAL", "综合能力"},
        {"你在做技术方案选型时，通常考虑哪些因素？请举例说明。", "GENERAL", "综合能力"},
        {"请分享一次你处理线上故障的经历，从发现到修复的完整过程。", "GENERAL", "综合能力"},
        {"你如何保证代码质量？介绍你实践过的有效手段。", "GENERAL", "综合能力"},
        {"描述一个你做过的技术优化案例，优化的动机、方案和效果。", "GENERAL", "综合能力"},
        {"你在团队协作中遇到过最大的分歧是什么？如何解决的？", "GENERAL", "综合能力"},
    };

    private final PromptTemplate skillSystemPromptTemplate;
    private final PromptTemplate skillUserPromptTemplate;
    private final PromptTemplate resumeSystemPromptTemplate;
    private final PromptTemplate resumeUserPromptTemplate;
    private final BeanOutputConverter<QuestionListDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final InterviewSkillService skillService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final ExecutorService questionExecutor;
    private final int followUpCount;

    private record QuestionListDTO(List<QuestionDTO> questions) {}

    private record QuestionDTO(
        String question,
        String type,
        String category,
        String topicSummary,
        String competency,
        List<String> keyPoints,
        String followUpDirection,
        List<RubricLevelDTO> rubric,
        List<String> followUps,
        List<String> trainingTargetIds
    ) {}

    private record RubricLevelDTO(int level, String criteria) {}

    public InterviewQuestionService(
            StructuredOutputInvoker structuredOutputInvoker,
            InterviewSkillService skillService,
            InterviewQuestionProperties properties,
            ResourceLoader resourceLoader,
            LlmProviderRegistry llmProviderRegistry,
            PromptSanitizer promptSanitizer) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.skillService = skillService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.questionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.skillSystemPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionSystemPromptPath());
        this.skillUserPromptTemplate = loadTemplate(resourceLoader, properties.getQuestionUserPromptPath());
        this.resumeSystemPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionSystemPromptPath());
        this.resumeUserPromptTemplate = loadTemplate(resourceLoader, properties.getResumeQuestionUserPromptPath());
        this.outputConverter = new BeanOutputConverter<>(QuestionListDTO.class);
        this.followUpCount = Math.max(0, Math.min(properties.getFollowUpCount(), MAX_FOLLOW_UP_COUNT));
    }

    private static PromptTemplate loadTemplate(ResourceLoader loader, String location) throws IOException {
        return new PromptTemplate(loader.getResource(location).getContentAsString(StandardCharsets.UTF_8));
    }

    @PreDestroy
    void destroy() {
        questionExecutor.shutdownNow();
    }

    public List<InterviewQuestionDTO> generateQuestionsBySkill(
            String llmProvider,
            String skillId,
            String difficulty,
            String resumeText,
            int questionCount,
            List<HistoricalQuestion> historicalQuestions,
            List<TrainingTask> priorTrainingTasks,
            List<CategoryDTO> customCategories,
            String jdText) {

        SkillDTO skill = resolveSkill(skillId, customCategories, jdText);
        String difficultyDesc = resolveDifficulty(difficulty);
        ChatClient questionChatClient =
            llmProviderRegistry.getPlainChatClient(llmProvider);

        boolean hasResume = resumeText != null && !resumeText.isBlank();
        String historicalSection = buildHistoricalSection(historicalQuestions);
        List<TrainingTargetSelector.Target> trainingTargets = TrainingTargetSelector.select(priorTrainingTasks);
        String trainingFocusSection = buildTrainingFocusSection(trainingTargets);
        if (!hasResume) {
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection, trainingFocusSection, trainingTargets);
        }

        int resumeCount = Math.max(1, (int) Math.round(questionCount * RESUME_QUESTION_RATIO));
        int directionCount = questionCount - resumeCount;

        log.info("并行出题: skill={}, total={}, resumeCount={}, directionCount={}",
            skillId, questionCount, resumeCount, directionCount);

        CompletableFuture<List<InterviewQuestionDTO>> resumeFuture = CompletableFuture.supplyAsync(
            () -> generateResumeQuestions(questionChatClient, resumeText, resumeCount, skill,
                difficultyDesc, historicalSection, trainingFocusSection, trainingTargets),
            questionExecutor);

        CompletableFuture<List<InterviewQuestionDTO>> directionFuture = CompletableFuture.supplyAsync(
            () -> generateDirectionOnly(questionChatClient, skill, difficultyDesc, directionCount,
                historicalSection, trainingFocusSection, trainingTargets),
            questionExecutor);

        List<InterviewQuestionDTO> resumeQuestions;
        List<InterviewQuestionDTO> directionQuestions;
        try {
            resumeQuestions = resumeFuture.join();
        } catch (CompletionException e) {
            log.error("简历题生成失败，降级为全方向题", e.getCause());
            directionFuture.cancel(true);
            return generateDirectionOnly(questionChatClient, skill, difficultyDesc, questionCount,
                historicalSection, trainingFocusSection, trainingTargets);
        }

        try {
            directionQuestions = directionFuture.join();
        } catch (CompletionException e) {
            log.error("方向题生成失败，降级为全简历题", e.getCause());
            if (resumeQuestions.isEmpty()) {
                return generateFallbackQuestions(skill, questionCount);
            }
            return resumeQuestions;
        }

        if (resumeQuestions.isEmpty() && directionQuestions.isEmpty()) {
            log.warn("简历题和方向题均为空，回退到默认问题");
            return generateFallbackQuestions(skill, questionCount);
        }

        List<InterviewQuestionDTO> merged = mergeQuestionBatches(resumeQuestions, directionQuestions);
        log.info("并行出题成功: 简历题={}, 方向题={}, 合计={}",
            resumeQuestions.size(), directionQuestions.size(), merged.size());
        return merged;
    }

    private List<InterviewQuestionDTO> generateResumeQuestions(
            ChatClient questionClient, String resumeText, int questionCount,
            SkillDTO skill, String difficultyDesc, String historicalSection,
            String trainingFocusSection, List<TrainingTargetSelector.Target> trainingTargets) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("resumeText", resumeText);
            variables.put("historicalSection", historicalSection);
            variables.put("trainingFocusSection", trainingFocusSection);
            variables.put("typeKeySection", buildTypeKeySection(skill.categories()));

            String systemPrompt = resumeSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + "\n\n" + outputConverter.getFormat();
            String userPrompt = resumeUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "简历题生成失败：", "简历题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto, "RESUME", trainingTargets, skill.categories());
            questions = capToMainCount(questions, questionCount);
            log.info("简历题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("简历题生成异常: {}", e.getMessage(), e);
            throw e;
        }
    }

    private List<InterviewQuestionDTO> generateDirectionOnly(
            ChatClient questionClient, SkillDTO skill, String difficultyDesc,
            int questionCount, String historicalSection, String trainingFocusSection,
            List<TrainingTargetSelector.Target> trainingTargets) {
        Map<String, Integer> allocation = skillService.calculateAllocation(skill.categories(), questionCount);
        String allocationTable = skillService.buildAllocationDescription(allocation, skill.categories());

        log.info("方向题生成: skill={}, total={}, allocation={}",
            skill.id(), questionCount, allocation);

        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("questionCount", questionCount);
            variables.put("followUpCount", followUpCount);
            variables.put("difficultyDescription", difficultyDesc);
            variables.put("skillName", skill.name());
            variables.put("skillDescription", skill.description() != null ? skill.description() : "");
            variables.put("allocationTable", allocationTable);
            variables.put("historicalSection", historicalSection);
            variables.put("trainingFocusSection", trainingFocusSection);
            variables.put("referenceSection", skillService.buildReferenceSection(skill, allocation));
            variables.put("jdSection", buildJdSection(skill.sourceJd()));

            String systemPrompt = skillSystemPromptTemplate.render()
                + buildSkillPersonaSection(skill)
                + GENERIC_MODE_SYSTEM_APPEND
                + outputConverter.getFormat();
            String userPrompt = skillUserPromptTemplate.render(variables);

            QuestionListDTO dto = structuredOutputInvoker.invoke(
                questionClient, systemPrompt, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "方向题生成失败：", "方向题", log);

            List<InterviewQuestionDTO> questions = convertToQuestions(dto, "SKILL_REFERENCE", trainingTargets, skill.categories());
            if (questions.stream().filter(q -> !q.isFollowUp()).count() == 0) {
                log.warn("方向题返回空题单，回退到默认问题");
                return generateFallbackQuestions(skill, questionCount);
            }
            questions = capToMainCount(questions, questionCount);
            log.info("方向题生成完成: 请求={}, 实际主问题={}",
                questionCount, questions.stream().filter(q -> !q.isFollowUp()).count());
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("方向题生成失败，回退到默认问题: {}", e.getMessage(), e);
            return generateFallbackQuestions(skill, questionCount);
        }
    }

    private List<InterviewQuestionDTO> mergeQuestionBatches(
            List<InterviewQuestionDTO> first, List<InterviewQuestionDTO> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        int offset = first.size();
        List<InterviewQuestionDTO> merged = new ArrayList<>(first);
        for (InterviewQuestionDTO q : second) {
            int newIndex = q.questionIndex() + offset;
            Integer newParent = q.parentQuestionIndex() != null
                ? q.parentQuestionIndex() + offset : null;
            merged.add(InterviewQuestionDTO.create(
                newIndex, q.question(), q.type(), q.category(),
                q.topicSummary(), q.isFollowUp(), newParent, q.evaluationGuide()));
        }
        return merged;
    }

    private SkillDTO resolveSkill(String skillId, List<CategoryDTO> customCategories, String jdText) {
        if (InterviewSkillService.CUSTOM_SKILL_ID.equals(skillId)
                && customCategories != null && !customCategories.isEmpty()) {
            return skillService.buildCustomSkill(customCategories, jdText != null ? jdText : "");
        }
        return skillService.getSkill(skillId);
    }

    private String resolveDifficulty(String difficulty) {
        return DIFFICULTY_DESCRIPTIONS.getOrDefault(
            difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY,
            DIFFICULTY_DESCRIPTIONS.get(InterviewDefaults.DIFFICULTY));
    }

    private List<InterviewQuestionDTO> convertToQuestions(QuestionListDTO dto, String source,
            List<TrainingTargetSelector.Target> trainingTargets, List<SkillCategoryDTO> categories) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (dto == null || dto.questions() == null) {
            return questions;
        }

        for (QuestionDTO q : dto.questions()) {
            if (q == null || q.question() == null || q.question().isBlank()) {
                continue;
            }
            String type = resolveQuestionType(q, categories);
            int mainQuestionIndex = index;
            QuestionEvaluationGuide guide = buildEvaluationGuide(q, type, source, trainingTargets);
            questions.add(InterviewQuestionDTO.create(
                index++, q.question(), type, q.category(), q.topicSummary(), false, null, guide));

            List<String> followUps = sanitizeFollowUps(q.followUps());
            for (int i = 0; i < followUps.size(); i++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, followUps.get(i), type,
                    buildFollowUpCategory(q.category(), i + 1), null, true, mainQuestionIndex,
                    guide
                ));
            }
        }

        return questions;
    }

    private QuestionEvaluationGuide buildEvaluationGuide(
            QuestionDTO question, String type, String source,
            List<TrainingTargetSelector.Target> trainingTargets) {
        String competency = firstNonBlank(question.competency(), question.category(), type);
        List<String> keyPoints = question.keyPoints() != null && !question.keyPoints().isEmpty()
            ? question.keyPoints()
            : question.topicSummary() != null && !question.topicSummary().isBlank()
                ? List.of(question.topicSummary())
                : List.of();
        List<RubricLevel> rubric = question.rubric() == null
            ? List.of()
            : question.rubric().stream()
                .filter(item -> item != null)
                .map(item -> new RubricLevel(item.level(), item.criteria()))
                .toList();
        return new QuestionEvaluationGuide(
            competency,
            keyPoints,
            firstNonBlank(
                question.followUpDirection(),
                "根据候选人的缺失点追问原理、边界或实际验证方式"),
            source,
            rubric,
            validateTrainingTargetIds(question.trainingTargetIds(), trainingTargets)
        );
    }

  private List<String> validateTrainingTargetIds(List<String> ids,
      List<TrainingTargetSelector.Target> targets) {
    if (ids == null) return List.of();
    List<String> distinctIds = ids.stream().filter(id -> id != null && !id.isBlank())
        .map(String::trim).distinct().toList();
    if (distinctIds.size() != 1) return List.of();
    String id = distinctIds.getFirst();
    return targets.stream().anyMatch(target -> target.id().equals(id)) ? List.of(id) : List.of();
  }

  private String resolveQuestionType(QuestionDTO question, List<SkillCategoryDTO> categories) {
    if (categories == null || categories.isEmpty()) return DEFAULT_QUESTION_TYPE;
    for (SkillCategoryDTO category : categories) {
      if (sameClassification(category.key(), question.type())) return category.key();
    }
    for (SkillCategoryDTO category : categories) {
      if (sameClassification(category.key(), question.category())
          || sameClassification(category.label(), question.category())) return category.key();
    }
    throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "题目分类不在当前面试方向，请重新生成题目");
  }

  private boolean sameClassification(String expected, String actual) {
    return expected != null && actual != null && expected.trim().equalsIgnoreCase(actual.trim());
  }

  private String buildTypeKeySection(List<SkillCategoryDTO> categories) {
    String keys = categories.stream().map(category -> "- " + category.key() + "：" + category.label())
        .collect(Collectors.joining("\n"));
    return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
        + promptSanitizer.wrapWithDelimiters("type_keys", promptSanitizer.sanitize(keys));
  }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return "综合能力";
    }

    /**
     * 将问题列表截断到指定的主问题数量（AI 多生时截断，少生时保留原样并记录警告）。
     */
    private List<InterviewQuestionDTO> capToMainCount(
            List<InterviewQuestionDTO> questions, int maxMainCount) {
        long currentMainCount = questions.stream().filter(q -> !q.isFollowUp()).count();

        if (currentMainCount <= maxMainCount) {
            if (currentMainCount < maxMainCount) {
                log.warn("AI 生成主问题不足: 请求={}, 实际={}", maxMainCount, currentMainCount);
            }
            return questions;
        }

        List<InterviewQuestionDTO> capped = new ArrayList<>();
        int mainSeen = 0;
        for (InterviewQuestionDTO q : questions) {
            if (!q.isFollowUp()) {
                mainSeen++;
            }
            if (mainSeen > maxMainCount) {
                break;
            }
            capped.add(q);
        }
        log.info("题目截断: 主问题 {} → {}", currentMainCount, maxMainCount);
        return capped;
    }

    private List<InterviewQuestionDTO> generateFallbackQuestions(SkillDTO skill, int count) {
        List<SkillCategoryDTO> categories = skill != null ? skill.categories() : List.of();
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        int index = 0;

        if (!categories.isEmpty()) {
            int generated = 0;
            while (generated < count) {
                SkillCategoryDTO cat = categories.get(generated % categories.size());
                String question = "请谈谈你在\"" + cat.label() + "\"方向的技术理解和实践经验。";
                QuestionEvaluationGuide guide = new QuestionEvaluationGuide(
                    cat.label(), List.of(), "追问实际使用、核心原理和失败边界", "FALLBACK",
                    List.of(), List.of());
                questions.add(InterviewQuestionDTO.create(
                    index++, question, cat.key(), cat.label(), null, false, null, guide));
                int mainIndex = index - 1;
                for (int j = 0; j < followUpCount; j++) {
                    questions.add(InterviewQuestionDTO.create(
                        index++, buildDefaultFollowUp(question, j + 1),
                        cat.key(), buildFollowUpCategory(cat.label(), j + 1), null, true, mainIndex,
                        guide
                    ));
                }
                generated++;
            }
            return questions;
        }

        for (int i = 0; i < Math.min(count, GENERIC_FALLBACK_QUESTIONS.length); i++) {
            String[] q = GENERIC_FALLBACK_QUESTIONS[i];
            QuestionEvaluationGuide guide = new QuestionEvaluationGuide(
                q[2], List.of(), "追问真实行为、决策依据和结果证据", "FALLBACK",
                List.of(), List.of());
            questions.add(InterviewQuestionDTO.create(
                index++, q[0], q[1], q[2], null, false, null, guide));
            int mainIndex = index - 1;
            for (int j = 0; j < followUpCount; j++) {
                questions.add(InterviewQuestionDTO.create(
                    index++, buildDefaultFollowUp(q[0], j + 1),
                    q[1], buildFollowUpCategory(q[2], j + 1), null, true, mainIndex,
                    guide
                ));
            }
        }
        return questions;
    }

    private String buildHistoricalSection(List<HistoricalQuestion> historicalQuestions) {
        if (historicalQuestions == null || historicalQuestions.isEmpty()) {
            return "暂无历史提问";
        }

        Map<String, List<String>> grouped = new HashMap<>();
        for (HistoricalQuestion hq : historicalQuestions) {
            String type = hq.type() != null && !hq.type().isBlank() ? hq.type() : DEFAULT_QUESTION_TYPE;
            String summary = hq.topicSummary();
            if (summary == null || summary.isBlank()) {
                String q = hq.question();
                summary = q.length() > 30 ? q.substring(0, 30) + "…" : q;
            }
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(summary);
        }

        StringBuilder sb = new StringBuilder("已考过的知识点（避免重复出题）：\n");
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ");
            sb.append(String.join(", ", entry.getValue()));
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildTrainingFocusSection(List<TrainingTargetSelector.Target> targets) {
        if (targets.isEmpty()) {
            return "暂无历史训练目标；按当前题目分布出题，trainingTargetIds 设为 []。";
        }
        StringBuilder focus = new StringBuilder();
        focus.append("以下是上一场评估产生的训练目标，仅用于安排本场问题；不要把原因当成候选人已承认的事实。\n");
        targets.forEach(target -> focus.append("- 目标 ID：").append(target.id())
                .append("；能力点：").append(target.task().competency().trim())
                .append("；优先级：").append(target.task().priority())
                .append("；待复核原因：")
                .append(trainingField(target.task().reason()))
                .append("；练习动作：").append(trainingField(target.task().action()))
                .append("；完成标准：").append(trainingField(target.task().completionCriteria()))
                .append('\n'));
        focus.append("在不偏离当前 Skill、简历和题目数量约束的前提下，至少安排一个主问题检验优先级最高且与当前方向相关的目标。问题和评分要点应检查练习动作与完成标准，不要求复述旧原因。关联该目标时 trainingTargetIds 填入它的完整 ID，每题至多一个；无关或无法检验时设为 []，不为了覆盖率强行关联。\n");
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
            + promptSanitizer.wrapWithDelimiters("training_focus", promptSanitizer.sanitize(focus.toString()));
    }

  private String trainingField(String value) {
    return value == null || value.isBlank() ? "未提供" : value.trim();
  }

    private String buildJdSection(String sourceJd) {
        if (sourceJd == null || sourceJd.isBlank()) {
            return "";
        }
        return PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n" +
            "## 职位描述（JD）\n根据以下 JD 关键要求出题，确保题目与岗位实际需求相关：\n" +
            promptSanitizer.wrapWithDelimiters("jd", promptSanitizer.sanitize(sourceJd));
    }

    private String buildSkillPersonaSection(SkillDTO skill) {
        if (skill == null || skill.persona() == null || skill.persona().isBlank()) {
            return "";
        }
        return "\n\n# Skill Persona\n"
            + "以下内容来自当前面试方向的 SKILL.md，请作为面试官角色、风格与出题约束：\n"
            + promptSanitizer.wrapWithDelimiters("skill_persona", skill.persona());
    }

    private List<String> sanitizeFollowUps(List<String> followUps) {
        if (followUpCount == 0 || followUps == null || followUps.isEmpty()) {
            return List.of();
        }
        return followUps.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .limit(followUpCount)
            .collect(Collectors.toList());
    }

    private String buildFollowUpCategory(String category, int order) {
        String base = (category == null || category.isBlank()) ? "追问" : category;
        return base + "（追问" + order + "）";
    }

    private String buildDefaultFollowUp(String mainQuestion, int order) {
        if (order == 1) {
            return "基于\"" + mainQuestion + "\"，请结合你亲自做过的一个真实场景展开说明。";
        }
        return "基于\"" + mainQuestion + "\"，如果线上出现异常，你会如何定位并给出修复方案？";
    }
}
