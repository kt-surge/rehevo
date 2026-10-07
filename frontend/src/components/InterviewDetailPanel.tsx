import {useMemo, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {getScoreColor} from '../utils/score';
import type {InterviewDetail} from '../api/history';

interface InterviewDetailPanelProps {
  interview: InterviewDetail;
}

/**
 * 面试详情面板组件
 */
export default function InterviewDetailPanel({ interview }: InterviewDetailPanelProps) {
  // 默认展开所有题目
  const [expandedQuestions, setExpandedQuestions] = useState<Set<number>>(() => {
    const allIndices = new Set<number>();
    if (interview.answers) {
      interview.answers.forEach((_, idx) => allIndices.add(idx));
    }
    return allIndices;
  });

  const toggleQuestion = (index: number) => {
    setExpandedQuestions(prev => {
      const newSet = new Set(prev);
      if (newSet.has(index)) {
        newSet.delete(index);
      } else {
        newSet.add(index);
      }
      return newSet;
    });
  };

  // 计算圆环进度
  const { scorePercent, circumference, strokeDashoffset } = useMemo(() => {
    const percent = interview.overallScore !== null ? (interview.overallScore / 100) * 100 : 0;
    const circ = 2 * Math.PI * 54; // r=54
    const offset = circ - (percent / 100) * circ;
    return { scorePercent: percent, circumference: circ, strokeDashoffset: offset };
  }, [interview.overallScore]);

  return (
      <motion.div
      className="space-y-6"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
    >
      {/* 评分卡片 */}
      <ScoreCard
        score={interview.overallScore}
        feedback={interview.overallFeedback}
        scorePercent={scorePercent}
        circumference={circumference}
        strokeDashoffset={strokeDashoffset}
      />

      {interview.answeredQuestions !== undefined && (
        <EvaluationQualitySummary interview={interview} />
      )}

      {/* 表现优势 */}
      {interview.strengths && interview.strengths.length > 0 && (
        <StrengthsSection strengths={interview.strengths} />
      )}

      {/* 改进建议 */}
      {interview.improvements && interview.improvements.length > 0 && (
        <ImprovementsSection improvements={interview.improvements} />
      )}

      {interview.trainingTasks && interview.trainingTasks.length > 0 && (
        <TrainingTasksSection tasks={interview.trainingTasks} />
      )}

      {/* 问答记录详情 */}
        <QuestionsSection
        answers={interview.answers || []}
        expandedQuestions={expandedQuestions}
        toggleQuestion={toggleQuestion}
      />
    </motion.div>
  );
}

function EvaluationQualitySummary({ interview }: { interview: InterviewDetail }) {
  const percentage = (value?: number) => `${Math.round((value ?? 0) * 100)}%`;
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <div className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <p className="text-xs text-slate-500 dark:text-slate-400">有效评分</p>
        <p className="mt-1 text-xl font-semibold text-slate-800 dark:text-white">
          {interview.scoredQuestions ?? 0}/{interview.answeredQuestions ?? 0}
        </p>
        {(interview.failedQuestions ?? 0) > 0 && (
          <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
            {interview.failedQuestions} 题评估失败，未计入总分
          </p>
        )}
      </div>
      <div className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <p className="text-xs text-slate-500 dark:text-slate-400">评分覆盖率</p>
        <p className="mt-1 text-xl font-semibold text-slate-800 dark:text-white">
          {percentage(interview.evaluationCoverage)}
        </p>
        <p className="mt-1 text-xs text-slate-400">成功评分 ÷ 已回答</p>
      </div>
      <div className="rounded-2xl border border-slate-200/80 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <p className="text-xs text-slate-500 dark:text-slate-400">证据覆盖率</p>
        <p className="mt-1 text-xl font-semibold text-slate-800 dark:text-white">
          {percentage(interview.evidenceCoverage)}
        </p>
        <p className="mt-1 text-xs text-slate-400">有原文证据 ÷ 成功评分</p>
      </div>
    </div>
  );
}

// 评分卡片组件
function ScoreCard({
  score,
  feedback,
  // scorePercent, // 暂时未使用
  circumference,
  strokeDashoffset
}: {
  score: number | null;
  feedback: string | null;
  scorePercent: number;
  circumference: number;
  strokeDashoffset: number;
}) {
  return (
    <div className="rounded-[28px] border border-primary-600 bg-primary-700 p-8 text-white shadow-md shadow-primary-950/15">
      <div className="flex flex-col items-center text-center">
        {/* 圆环进度条 */}
        <div className="relative w-32 h-32 mb-6">
          <svg className="w-32 h-32 transform -rotate-90" viewBox="0 0 120 120">
            <circle
              cx="60"
              cy="60"
              r="54"
              stroke="rgba(255,255,255,0.2)"
              strokeWidth="8"
              fill="none"
            />
            <motion.circle
              cx="60"
              cy="60"
              r="54"
              stroke="white"
              strokeWidth="8"
              fill="none"
              strokeLinecap="round"
              strokeDasharray={circumference}
              initial={{ strokeDashoffset: circumference }}
              animate={{ strokeDashoffset }}
              transition={{ duration: 1.5, ease: "easeOut" }}
            />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <motion.span
              className="text-4xl font-bold"
              initial={{ opacity: 0, scale: 0.5 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: 0.5 }}
            >
              {score ?? '-'}
            </motion.span>
            <span className="text-sm text-white/70">总分</span>
          </div>
        </div>

        <h3 className="text-2xl font-bold mb-3">面试评估</h3>
        <p className="text-white/90 max-w-2xl leading-relaxed">
          {feedback || '表现良好，展示了扎实的技术基础。'}
        </p>
      </div>
    </div>
  );
}

// 优势部分组件
function StrengthsSection({ strengths }: { strengths: string[] }) {
  return (
      <motion.div
          className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.1 }}
    >
        <h4 className="font-semibold text-emerald-600 dark:text-emerald-400 mb-4 flex items-center gap-2">
        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
          <polyline points="22,4 12,14.01 9,11.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
        表现优势
      </h4>
      <ul className="space-y-3">
        {strengths.map((s: string, i: number) => (
            <li key={i} className="text-slate-700 dark:text-slate-300 flex items-start gap-3">
            <span className="w-2 h-2 bg-primary-500 rounded-full mt-2 flex-shrink-0"></span>
            <span>{s}</span>
          </li>
        ))}
      </ul>
    </motion.div>
  );
}

// 改进建议部分组件
function ImprovementsSection({ improvements }: { improvements: string[] }) {
  return (
      <motion.div
          className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-sm dark:border-slate-800 dark:bg-slate-900"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.2 }}
    >
        <h4 className="font-semibold text-amber-600 dark:text-amber-400 mb-4 flex items-center gap-2">
        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
          <line x1="12" y1="8" x2="12" y2="12" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
          <line x1="12" y1="16" x2="12.01" y2="16" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
        </svg>
        改进建议
      </h4>
      <ul className="space-y-3">
        {improvements.map((s: string, i: number) => (
            <li key={i} className="text-slate-700 dark:text-slate-300 flex items-start gap-3">
            <span className="w-2 h-2 bg-amber-500 rounded-full mt-2 flex-shrink-0"></span>
            <span>{s}</span>
          </li>
        ))}
      </ul>
    </motion.div>
  );
}

function TrainingTasksSection({ tasks }: { tasks: NonNullable<InterviewDetail['trainingTasks']> }) {
  return (
    <motion.div
      className="rounded-2xl border border-primary-100 bg-primary-50/50 p-6 dark:border-primary-900/40 dark:bg-primary-950/20"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.25 }}
    >
      <h4 className="mb-4 font-semibold text-primary-700 dark:text-primary-300">下一轮训练任务</h4>
      <div className="space-y-3">
        {[...tasks].sort((left, right) => right.priority - left.priority).map(task => (
          <div key={`${task.competency}-${task.priority}`} className="rounded-xl bg-white/80 p-4 dark:bg-slate-900/70">
            <div className="flex items-center gap-2">
              <span className="rounded-full bg-primary-100 px-2 py-0.5 text-xs font-semibold text-primary-700 dark:bg-primary-900/40 dark:text-primary-200">
                P{task.priority}
              </span>
              <span className="font-medium text-slate-800 dark:text-white">{task.competency}</span>
              {task.questionIndexes?.length > 0 && (
                <span className="text-xs text-slate-500">关联题目：{task.questionIndexes.map(index => index + 1).join('、')}</span>
              )}
            </div>
            <p className="mt-2 text-sm text-slate-600 dark:text-slate-300">原因：{task.reason}</p>
            <p className="mt-1 text-sm text-slate-700 dark:text-slate-200">行动：{task.action}</p>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">完成标准：{task.completionCriteria}</p>
          </div>
        ))}
      </div>
    </motion.div>
  );
}

// 问答部分组件
function QuestionsSection({
  answers,
  expandedQuestions,
  toggleQuestion
}: {
  answers: any[];
  expandedQuestions: Set<number>;
  toggleQuestion: (index: number) => void;
}) {
  return (
    <div>
      <h4 className="font-semibold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
        <svg className="w-5 h-5 text-primary-500" viewBox="0 0 24 24" fill="none">
          <path d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
        问答记录详情
      </h4>

      <div className="space-y-4">
        {answers.map((answer, idx) => (
          <QuestionCard
            key={idx}
            answer={answer}
            index={idx}
            isExpanded={expandedQuestions.has(idx)}
            onToggle={() => toggleQuestion(idx)}
          />
        ))}
      </div>
    </div>
  );
}

// 问题卡片组件
function QuestionCard({
  answer,
  index,
  isExpanded,
  onToggle
}: {
  answer: any;
  index: number;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
      <motion.div
          className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: 0.1 + index * 0.05 }}
    >
      {/* 问题头部 */}
        <div
            className="flex cursor-pointer items-center justify-between px-5 py-4 transition-colors hover:bg-slate-50 dark:hover:bg-slate-800/70"
        onClick={onToggle}
      >
        <div className="flex items-center gap-3">
          <span
              className="w-8 h-8 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-lg flex items-center justify-center text-sm font-semibold">
            {answer.questionIndex + 1}
          </span>
          <span
              className="px-3 py-1 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-xs font-medium rounded-full">
            {answer.category || '综合'}
          </span>
          <span className={`font-semibold ${answer.evaluationStatus === 'EVALUATION_FAILED'
            ? 'text-amber-500'
            : answer.evaluationStatus === 'UNANSWERED'
              ? 'text-slate-400'
              : getScoreColor(answer.score, [80, 60])}`}>
            {answer.evaluationStatus === 'EVALUATION_FAILED'
              ? '评估失败'
              : answer.evaluationStatus === 'UNANSWERED'
                ? '未回答'
                : `得分: ${answer.score}`}
          </span>
        </div>
          <motion.svg
          className="w-5 h-5 text-slate-400"
          animate={{ rotate: isExpanded ? 180 : 0 }}
          transition={{ duration: 0.2 }}
          viewBox="0 0 24 24"
          fill="none"
        >
          <polyline points="6,9 12,15 18,9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
        </motion.svg>
      </div>

      {/* 问题内容 */}
      <div className="px-5 pb-2">
        <p className="text-slate-800 dark:text-white font-medium leading-relaxed">{answer.question}</p>
      </div>

      {/* 展开内容 */}
      <AnimatePresence>
        {isExpanded && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.3 }}
            className="overflow-hidden"
          >
            <div className="px-5 pb-5 space-y-4">
              {/* 你的回答 */}
              <div className="rounded-xl border border-slate-100 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-950/30">
                <p className="text-sm text-slate-500 dark:text-slate-400 mb-2 flex items-center gap-1">
                  <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none">
                    <path d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                  你的回答
                </p>
                <p className={`leading-relaxed ${
                  !answer.userAnswer || answer.userAnswer === '不知道' 
                    ? 'text-red-500 font-medium'
                      : 'text-slate-700 dark:text-slate-300'
                }`}>
                  "{answer.userAnswer || '(未回答)'}"
                </p>
              </div>

              {/* AI 深度评价 */}
              {answer.feedback && (
                <div>
                  <p className="text-sm text-slate-600 dark:text-slate-400 mb-2 flex items-center gap-2 font-medium">
                    <svg className="w-4 h-4 text-primary-500" viewBox="0 0 24 24" fill="none">
                      <path d="M3 3V21H21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                      <path d="M18 9L12 15L9 12L3 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    </svg>
                    AI 深度评价
                  </p>
                  <p className="text-slate-700 dark:text-slate-300 leading-relaxed pl-6">{answer.feedback}</p>
                </div>
              )}

              {answer.evaluationStatus === 'SCORED' && answer.rubricLevel !== undefined && (
                <div className="flex items-center gap-2 text-sm">
                  <span className="font-medium text-slate-600 dark:text-slate-400">Rubric 等级</span>
                  <span className="rounded-full bg-primary-50 px-2.5 py-1 font-semibold text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
                    Level {answer.rubricLevel}
                  </span>
                </div>
              )}

              {answer.answerEvidence?.length > 0 && (
                <div className="rounded-xl border border-emerald-100 bg-emerald-50/70 p-4 dark:border-emerald-900/40 dark:bg-emerald-950/20">
                  <p className="mb-2 text-sm font-medium text-emerald-700 dark:text-emerald-300">回答证据</p>
                  <ul className="space-y-1 text-sm text-slate-700 dark:text-slate-300">
                    {answer.answerEvidence.map((item: string, evidenceIndex: number) => (
                      <li key={evidenceIndex}>“{item}”</li>
                    ))}
                  </ul>
                </div>
              )}

              {answer.missingPoints?.length > 0 && (
                <div className="rounded-xl border border-amber-100 bg-amber-50/70 p-4 dark:border-amber-900/40 dark:bg-amber-950/20">
                  <p className="mb-2 text-sm font-medium text-amber-700 dark:text-amber-300">尚缺关键点</p>
                  <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300">
                    {answer.missingPoints.map((item: string, missingIndex: number) => (
                      <li key={missingIndex}>{item}</li>
                    ))}
                  </ul>
                </div>
              )}

              {answer.factualRisks?.length > 0 && (
                <div className="rounded-xl border border-red-100 bg-red-50/70 p-4 dark:border-red-900/40 dark:bg-red-950/20">
                  <p className="mb-2 text-sm font-medium text-red-700 dark:text-red-300">事实风险</p>
                  <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300">
                    {answer.factualRisks.map((item: string, riskIndex: number) => (
                      <li key={riskIndex}>{item}</li>
                    ))}
                  </ul>
                </div>
              )}

              {answer.nextAction && (
                <div className="rounded-xl border border-primary-100 bg-primary-50/60 p-4 dark:border-primary-900/40 dark:bg-primary-950/20">
                  <p className="mb-1 text-sm font-medium text-primary-700 dark:text-primary-300">下一步训练</p>
                  <p className="text-sm leading-relaxed text-slate-700 dark:text-slate-300">{answer.nextAction}</p>
                </div>
              )}

              {/* 参考答案 */}
              {answer.referenceAnswer && (
                  <div
                      className="rounded-xl border border-slate-100 bg-slate-50 p-4 dark:border-slate-800 dark:bg-slate-950/30">
                    <p className="text-sm text-slate-600 dark:text-slate-400 mb-3 flex items-center gap-2 font-medium">
                    <svg className="w-4 h-4 text-primary-500" viewBox="0 0 24 24" fill="none">
                      <rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" strokeWidth="2"/>
                      <path d="M9 12H15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
                      <path d="M12 9V15" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
                    </svg>
                    参考答案
                  </p>
                    <div
                        className="text-slate-700 dark:text-slate-300 leading-relaxed whitespace-pre-line">{answer.referenceAnswer}</div>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
