import {useMemo, useRef} from 'react';
import {motion} from 'framer-motion';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import type {InterviewQuestion, InterviewSession} from '../types/interview';
import {Send} from 'lucide-react';
import InterviewMessageBubble from './InterviewMessageBubble';

interface Message {
  type: 'interviewer' | 'user';
  content: string;
  category?: string;
  questionIndex?: number;
}

interface InterviewChatPanelProps {
  session: InterviewSession;
  currentQuestion: InterviewQuestion | null;
  messages: Message[];
  answer: string;
  onAnswerChange: (answer: string) => void;
  onSubmit: () => void;
  onCompleteEarly: () => void;
  isSubmitting: boolean;
  showCompleteConfirm: boolean;
  onShowCompleteConfirm: (show: boolean) => void;
}

/**
 * 面试聊天面板组件
 */
export default function InterviewChatPanel({
  session,
  currentQuestion,
  messages,
  answer,
  onAnswerChange,
  onSubmit,
  // onCompleteEarly, // 暂时未使用
  isSubmitting,
  // showCompleteConfirm, // 暂时未使用
  onShowCompleteConfirm
}: InterviewChatPanelProps) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);

  const progress = useMemo(() => {
    if (!session || !currentQuestion) return 0;
    return ((currentQuestion.questionIndex + 1) / session.totalQuestions) * 100;
  }, [session, currentQuestion]);

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      onSubmit();
    }
  };

  return (
    <div className="flex flex-col h-[calc(100vh-200px)] max-w-4xl mx-auto">
      {/* 进度条 */}
        <div
            className="bg-white dark:bg-slate-900 rounded-2xl p-6 mb-4 shadow-sm border border-slate-200/80 dark:border-slate-800">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-300">
            题目 {currentQuestion ? currentQuestion.questionIndex + 1 : 0} / {session.totalQuestions}
          </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">
            {Math.round(progress)}%
          </span>
        </div>
            <div className="h-2 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
          <motion.div
            className="h-full bg-primary-600 rounded-full"
            initial={{ width: 0 }}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.3 }}
          />
        </div>
      </div>

      {session.plan && (
        <details className="mb-4 rounded-2xl border border-primary-100 bg-primary-50/60 px-5 py-3 text-sm dark:border-primary-900/50 dark:bg-primary-950/20">
          <summary className="cursor-pointer font-semibold text-primary-800 dark:text-primary-200">
            本轮能力计划：覆盖 {session.plan.plannedCompetencies} 个能力点
            {session.plan.requestedFocusCompetencies > 0
              ? `，训练目标 ${session.plan.requestedFocusCompetencies} 项，已安排题目 ${session.plan.prioritizedCompetencies} 项（${Math.round(session.plan.retestCoverage * 100)}%）`
              : ''}
          </summary>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {session.plan.competencies.map((competency) => (
              <div key={competency.competency} className="rounded-xl bg-white/80 px-3 py-2 dark:bg-slate-900/70">
                <p className="font-medium text-slate-800 dark:text-slate-100">
                  {competency.competency} · {competency.plannedQuestions} 题
                </p>
                  {competency.evidenceChecklist.length > 0 && (
                  <p className="mt-1 line-clamp-2 text-xs text-slate-500 dark:text-slate-400">
                    关注：{competency.evidenceChecklist.join('、')}
                  </p>
                  )}
                {competency.priorityReasons.length > 0 && (
                  <p className="mt-1 line-clamp-2 text-xs text-amber-700 dark:text-amber-300">
                    优先原因：{competency.priorityReasons.join('；')}
                  </p>
                )}
              </div>
            ))}
          </div>
        </details>
      )}

      {/* 聊天区域 */}
        <div
            className="flex-1 bg-white dark:bg-slate-900 rounded-[28px] shadow-[0_18px_45px_-32px_rgba(24,34,53,0.45)] overflow-hidden flex flex-col min-h-0 border border-slate-200/80 dark:border-slate-800">
        <Virtuoso
          ref={virtuosoRef}
          data={messages}
          initialTopMostItemIndex={messages.length - 1}
          followOutput="smooth"
          className="flex-1"
          itemContent={(_index, msg) => (
            <div className="pb-4 px-6 first:pt-6">
              <InterviewMessageBubble
                role={msg.type === 'interviewer' ? 'interviewer' : 'user'}
                text={msg.content}
                category={msg.category}
              />
            </div>
          )}
        />

        {/* 输入区域 */}
            <div className="border-t border-slate-200 dark:border-slate-800 p-4 bg-slate-50/80 dark:bg-slate-950/40">
          <div className="flex gap-3">
            <textarea
              value={answer}
              onChange={(e) => onAnswerChange(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder="输入你的回答... (Ctrl/Cmd + Enter 提交)"
              className="flex-1 px-4 py-3 border border-slate-300 dark:border-slate-500 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-none bg-white dark:bg-slate-800 text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500"
              rows={3}
              disabled={isSubmitting}
            />
            <div className="flex flex-col gap-2">
              <motion.button
                onClick={onSubmit}
                disabled={!answer.trim() || isSubmitting}
                className="px-6 py-3 bg-primary-600 text-white rounded-xl font-medium hover:bg-primary-700 shadow-sm shadow-primary-500/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                whileHover={{ scale: isSubmitting || !answer.trim() ? 1 : 1.02 }}
                whileTap={{ scale: isSubmitting || !answer.trim() ? 1 : 0.98 }}
              >
                {isSubmitting ? (
                  <>
                    <motion.div
                      className="w-4 h-4 border-2 border-white border-t-transparent rounded-full"
                      animate={{ rotate: 360 }}
                      transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                    />
                    提交中
                  </>
                ) : (
                  <>
                    <Send className="w-4 h-4" />
                    提交
                  </>
                )}
              </motion.button>
              <motion.button
                onClick={() => onShowCompleteConfirm(true)}
                disabled={isSubmitting}
                className="px-6 py-3 bg-slate-200 dark:bg-slate-600 text-slate-700 dark:text-slate-200 rounded-xl font-medium hover:bg-slate-300 dark:hover:bg-slate-500 transition-colors disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                whileHover={{ scale: isSubmitting ? 1 : 1.02 }}
                whileTap={{ scale: isSubmitting ? 1 : 0.98 }}
              >
                提前交卷
              </motion.button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
