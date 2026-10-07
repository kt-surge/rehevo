package interview.guide.modules.interview.model;

import java.util.List;

/**
 * PREP 阶段的确定性会话计划。由已生成的问题和 Rubric 派生，不额外调用模型。
 */
public record InterviewPlan(
    String skillId,
    String difficulty,
    int plannedMainQuestions,
    int plannedCompetencies,
    int requestedFocusCompetencies,
    int prioritizedCompetencies,
    double retestCoverage,
    double competencyCoverage,
    List<CompetencyPlan> competencies,
    List<TrainingTargetPlan> trainingTargets
) {
  public InterviewPlan {
    trainingTargets = trainingTargets == null ? List.of() : List.copyOf(trainingTargets);
  }

  public InterviewPlan(String skillId, String difficulty, int plannedMainQuestions,
                       int plannedCompetencies, int requestedFocusCompetencies,
                       int prioritizedCompetencies, double retestCoverage,
                       double competencyCoverage, List<CompetencyPlan> competencies) {
    this(skillId, difficulty, plannedMainQuestions, plannedCompetencies, requestedFocusCompetencies,
        prioritizedCompetencies, retestCoverage, competencyCoverage, competencies, List.of());
  }

  public record TrainingTargetPlan(String targetId, String competency, String action,
                                   String completionCriteria, String reason, int priority,
                                   List<Integer> questionIndexes) {}
    public record CompetencyPlan(
        String competency,
        int plannedQuestions,
        List<Integer> questionIndexes,
        List<String> evidenceChecklist,
        List<String> sources,
        int priority,
        List<String> priorityReasons
    ) {
    }
}
