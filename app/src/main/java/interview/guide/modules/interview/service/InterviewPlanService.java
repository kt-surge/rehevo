package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Builds the PREP plan from the question plan already approved for this session. */
@Service
public class InterviewPlanService {

    public InterviewPlan build(String skillId, String difficulty, List<InterviewQuestionDTO> questions) {
        return build(skillId, difficulty, questions, List.of());
    }

    public InterviewPlan build(String skillId, String difficulty, List<InterviewQuestionDTO> questions,
                               List<TrainingTask> priorTrainingTasks) {
        List<InterviewQuestionDTO> mainQuestions = questions == null ? List.of() : questions.stream()
            .filter(question -> !question.isFollowUp())
            .toList();
        Map<String, MutableCompetencyPlan> byCompetency = new LinkedHashMap<>();
        List<TrainingTargetSelector.Target> targets = TrainingTargetSelector.select(priorTrainingTasks);
        Map<String, List<Integer>> assignments = new LinkedHashMap<>();
        int guidedQuestions = 0;
        for (InterviewQuestionDTO question : mainQuestions) {
            QuestionEvaluationGuide guide = question.evaluationGuide();
            String competency = guide != null && !guide.competency().isBlank()
                ? guide.competency() : fallbackCompetency(question.category());
            if (guide != null && !guide.competency().isBlank()) {
                guidedQuestions++;
            }
            MutableCompetencyPlan plan = byCompetency.computeIfAbsent(competency, ignored -> new MutableCompetencyPlan());
            plan.questionIndexes.add(question.questionIndex());
            for (TrainingTargetSelector.Target target : matchedTargets(guide, competency, targets)) {
                plan.targets.putIfAbsent(target.id(), target);
                assignments.computeIfAbsent(target.id(), ignored -> new ArrayList<>()).add(question.questionIndex());
            }
            if (guide != null) {
                plan.evidenceChecklist.addAll(guide.keyPoints());
                if (guide.source() != null && !guide.source().isBlank()) {
                    plan.sources.add(guide.source());
                }
            }
        }
        List<InterviewPlan.CompetencyPlan> competencies = byCompetency.entrySet().stream()
            .map(entry -> new InterviewPlan.CompetencyPlan(
                entry.getKey(),
                entry.getValue().questionIndexes.size(),
                List.copyOf(entry.getValue().questionIndexes),
                List.copyOf(entry.getValue().evidenceChecklist),
                List.copyOf(entry.getValue().sources),
                entry.getValue().targets.values().stream().mapToInt(target -> target.task().priority()).max().orElse(0),
                entry.getValue().targets.values().stream().map(target -> target.task().reason())
                    .filter(reason -> reason != null && !reason.isBlank()).distinct().limit(2).toList()
            ))
            .sorted(java.util.Comparator.comparingInt(InterviewPlan.CompetencyPlan::priority).reversed()
                .thenComparing(InterviewPlan.CompetencyPlan::competency))
            .toList();
        double coverage = mainQuestions.isEmpty() ? 0.0 : (double) guidedQuestions / mainQuestions.size();
        int requestedFocusCompetencies = targets.size();
        int prioritizedCompetencies = assignments.size();
        double retestCoverage = requestedFocusCompetencies == 0 ? 0.0
            : (double) prioritizedCompetencies / requestedFocusCompetencies;
        var targetPlans = targets.stream().map(target -> new InterviewPlan.TrainingTargetPlan(
            target.id(), target.task().competency(), target.task().action(), target.task().completionCriteria(),
            target.task().reason(), target.task().priority(),
            List.copyOf(assignments.getOrDefault(target.id(), List.of())))).toList();
        return new InterviewPlan(skillId, difficulty, mainQuestions.size(), competencies.size(),
            requestedFocusCompetencies, prioritizedCompetencies, retestCoverage, coverage, competencies, targetPlans);
    }

  private List<TrainingTargetSelector.Target> matchedTargets(QuestionEvaluationGuide guide,
      String competency, List<TrainingTargetSelector.Target> targets) {
    if (guide != null && guide.trainingTargetsDeclared()) {
      if (guide.trainingTargetIds().size() != 1) return List.of();
      return targets.stream().filter(target -> target.id().equals(guide.trainingTargetIds().getFirst())).toList();
    }
    // 仅旧题单兼容显示名；新题单显式未关联时不通过名称制造覆盖。
    return targets.stream().filter(target -> TrainingTargetSelector.normalize(target.task().competency())
        .equals(TrainingTargetSelector.normalize(competency))).toList();
  }

    private String fallbackCompetency(String category) {
        return category == null || category.isBlank() ? "综合能力" : category;
    }

    private static final class MutableCompetencyPlan {
        private final List<Integer> questionIndexes = new ArrayList<>();
        private final LinkedHashSet<String> evidenceChecklist = new LinkedHashSet<>();
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final Map<String, TrainingTargetSelector.Target> targets = new LinkedHashMap<>();
    }
}
