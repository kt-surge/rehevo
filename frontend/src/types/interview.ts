// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  totalQuestions: number;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
  plan: InterviewPlan;
}

export interface InterviewPlan {
  skillId: string;
  difficulty: string;
  plannedMainQuestions: number;
  plannedCompetencies: number;
  requestedFocusCompetencies: number;
  prioritizedCompetencies: number;
  retestCoverage: number;
  competencyCoverage: number;
  competencies: InterviewCompetencyPlan[];
  trainingTargets?: InterviewTrainingTarget[];
}

export interface InterviewTrainingTarget {
  targetId: string;
  competency: string;
  action: string | null;
  completionCriteria: string | null;
  reason: string | null;
  priority: number;
  questionIndexes: number[];
}

export interface InterviewCompetencyPlan {
  competency: string;
  plannedQuestions: number;
  questionIndexes: number[];
  evidenceChecklist: string[];
  sources: string[];
  priority: number;
  priorityReasons: string[];
}

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
}

export interface CreateInterviewRequest {
  resumeText: string;
  questionCount: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
}

export interface SubmitAnswerRequest {
  sessionId: string;
  questionIndex: number;
  answer: string;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
  liveFollowUpDecision: LiveFollowUpDecision;
}

export interface LiveFollowUpDecision {
  action: 'DEEPEN' | 'ADVANCE' | 'NOT_APPLICABLE';
  followUpQuestionIndex: number | null;
  matchedKeyPoints: number;
  totalKeyPoints: number;
  followUpRelevant: boolean;
  duplicateQuestion: boolean;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  answeredQuestions: number;
  scoredQuestions: number;
  failedQuestions: number;
  evidenceSupportedQuestions: number;
  evaluationCoverage: number;
  evidenceCoverage: number;
  overallScore: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
}

export interface CategoryScore {
  category: string;
  score: number;
  questionCount: number;
  answeredQuestionCount: number;
  scoredQuestionCount: number;
  evaluationCoverage: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  rubricLevel: number;
  answerEvidence: string[];
  missingPoints: string[];
  factualRisks: string[];
  nextAction: string;
  evaluationStatus: 'SCORED' | 'UNANSWERED' | 'EVALUATION_FAILED';
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}
