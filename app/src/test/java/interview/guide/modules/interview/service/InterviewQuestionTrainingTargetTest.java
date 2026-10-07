package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("真实出题装配中的训练内容与服务端关联校验")
class InterviewQuestionTrainingTargetTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final TrainingTask task = new TrainingTask("消息边界", List.of(0), "待复核原因",
      "分析提交后 ACK 前崩溃的恢复", "区分事务与确认并说明幂等性", 5);
  private final String id = TrainingTargetSelector.select(List.of(task)).getFirst().id();
  private InterviewQuestionService service;
  private String response;
  private String suppliedUser;

  @BeforeEach
  void setUp() throws Exception {
    var registry = mock(LlmProviderRegistry.class);
    var invoker = mock(StructuredOutputInvoker.class);
    var skills = mock(InterviewSkillService.class);
    var skill = new SkillDTO("java-backend", "Java 后端", "受控公共技术题",
        List.of(new SkillCategoryDTO("REDIS", task.competency(), "high", null, false)),
        true, null, null, null);
    when(registry.getPlainChatClient(null)).thenReturn(mock(ChatClient.class));
    when(skills.getSkill("java-backend")).thenReturn(skill);
    when(skills.calculateAllocation(anyList(), eq(1))).thenReturn(Map.of("REDIS", 1));
    when(skills.buildAllocationDescription(any(), anyList())).thenReturn("REDIS: 1");
    when(skills.buildReferenceSection(any(), any())).thenReturn("公开受控参考");
    when(invoker.invoke(any(), anyString(), anyString(), any(),
        eq(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED), anyString(), anyString(), any()))
        .thenAnswer(call -> {
          suppliedUser = call.getArgument(2);
          BeanOutputConverter<?> converter = call.getArgument(3);
          return converter.convert(response);
        });
    service = new InterviewQuestionService(invoker, skills, new InterviewQuestionProperties(),
        new DefaultResourceLoader(), registry, new PromptSanitizer(new LlmProviderProperties()));
  }

  @AfterEach
  void tearDown() {
    if (service != null) service.destroy();
  }

  @Test
  @DisplayName("提示词包含完整练习和验收内容且主问题追问保留同一关联")
  void sendsDefinitionAndKeepsKnownTarget() {
    response = questionJson("Redis 崩溃恢复", List.of(id));
    var questions = generate();
    assertThat(suppliedUser).contains(task.action(), task.completionCriteria(), id, "待复核原因");
    assertThat(questions).hasSize(2);
    assertThat(questions).allSatisfy(question -> {
      assertThat(question.evaluationGuide().trainingTargetIds()).containsExactly(id);
      assertThat(question.evaluationGuide().trainingTargetsDeclared()).isTrue();
    });
    assertThat(new InterviewPlanService().build("java-backend", "mid", questions, List.of(task))
        .trainingTargets().getFirst().questionIndexes()).containsExactly(0);
  }

  @Test
  @DisplayName("未知或多个目标在题目装配时清空且同名不能恢复关联")
  void rejectsUnknownAndMultipleAssociations() {
    for (List<String> ids : List.of(List.of("training:unknown"), List.of(id, "training:unknown"))) {
      response = questionJson(task.competency(), ids);
      var questions = generate();
      assertThat(questions.getFirst().evaluationGuide().trainingTargetIds()).isEmpty();
      assertThat(questions.getFirst().evaluationGuide().trainingTargetsDeclared()).isTrue();
      assertThat(new InterviewPlanService().build("java-backend", "mid", questions, List.of(task))
          .prioritizedCompetencies()).isZero();
    }
  }

  @Test
  @DisplayName("默认降级题即使同名也不能冒充针对训练标准生成的题")
  void fallbackDoesNotClaimRetest() {
    response = "{\"questions\":[]}";
    var questions = generate();
    assertThat(questions.getFirst().evaluationGuide().source()).isEqualTo("FALLBACK");
    assertThat(new InterviewPlanService().build("java-backend", "mid", questions, List.of(task))
        .prioritizedCompetencies()).isZero();
  }

  @Test
  @DisplayName("模型误用优先级作题型时只能依本次已给分类恢复")
  void resolvesPriorityConfusionFromKnownCategory() {
    response = questionJson("消息恢复", List.of(id)).replace("\"type\":\"REDIS\"", "\"type\":\"CORE\"");
    assertThat(generate()).allSatisfy(question -> assertThat(question.type()).isEqualTo("REDIS"));
  }

  @Test
  @DisplayName("题型和分类都不在本次Skill中时拒绝整批而不是伪造分类")
  void rejectsUnknownTypeAndCategory() {
    response = questionJson("消息恢复", List.of(id)).replace("\"type\":\"REDIS\"", "\"type\":\"OTHER\"")
        .replace("\"category\":\"Redis\"", "\"category\":\"unknown\"");
    assertThatThrownBy(this::generate).isInstanceOf(BusinessException.class)
        .hasMessageContaining("题目分类不在当前面试方向");
  }

  @Test
  @DisplayName("分配表同时提供明确分类key和显示名且不把优先级列作题型")
  void allocationIncludesKeys() throws Exception {
    var skills = new InterviewSkillService(mock(LlmProviderRegistry.class), mock(StructuredOutputInvoker.class),
        new DefaultResourceLoader(), new PromptSanitizer(new LlmProviderProperties()));
    String description = skills.buildAllocationDescription(Map.of("REDIS", 1),
        List.of(new SkillCategoryDTO("REDIS", "Redis", "CORE", null, false)));
    assertThat(description).contains("| REDIS | Redis | 1 题 | CORE |");
  }

  @Test
  @DisplayName("旧JSON可读取而新题的显式未关联状态跨持久化保持")
  void legacyJsonAndNewEmptyAssociationRemainDistinct() {
    String old = "{\"competency\":\"消息边界\",\"keyPoints\":[],\"source\":\"OLD\",\"rubric\":[]}";
    var legacy = mapper.readValue(old, QuestionEvaluationGuide.class);
    assertThat(legacy.trainingTargetsDeclared()).isFalse();
    assertThat(legacy.trainingTargetIds()).isEmpty();
    response = questionJson(task.competency(), List.of());
    var stored = mapper.readValue(mapper.writeValueAsString(generate().getFirst().evaluationGuide()),
        QuestionEvaluationGuide.class);
    assertThat(stored.trainingTargetsDeclared()).isTrue();
    assertThat(stored.trainingTargetIds()).isEmpty();
    var oldPlan = mapper.readValue("""
        {"skillId":"java-backend","difficulty":"mid","plannedMainQuestions":0,
         "plannedCompetencies":0,"requestedFocusCompetencies":0,"prioritizedCompetencies":0,
         "retestCoverage":0.0,"competencyCoverage":0.0,"competencies":[]}
        """, InterviewPlan.class);
    assertThat(oldPlan.trainingTargets()).isEmpty();
  }

  private List<InterviewQuestionDTO> generate() {
    return service.generateQuestionsBySkill(null, "java-backend", "mid", null, 1,
        List.of(), List.of(task), null, null);
  }

  private String questionJson(String label, List<String> ids) {
    return mapper.writeValueAsString(Map.of("questions", List.of(Map.of(
        "question", "提交后 ACK 前进程退出会怎样恢复？", "type", "REDIS", "category", "Redis",
        "competency", label, "keyPoints", List.of("崩溃窗口", "幂等"),
        "followUps", List.of("重复投递时怎样避免重复写入？"), "trainingTargetIds", ids))));
  }
}
