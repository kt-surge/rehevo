import {useEffect, useMemo, useRef, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import {knowledgeBaseApi, type KnowledgeBaseItem, type SortOption} from '../api/knowledgebase';
import {ragChatApi, type RagChatEvidence, type RagChatSessionListItem} from '../api/ragChat';
import {formatDateOnly} from '../utils/date';
import {evidenceAnchor, remarkRagCitations} from '../utils/ragCitationMarkdown';
import type {CitationValidationReport} from '../types/ragCitation';
import type {RagDisplayState, RagStreamEvent} from '../types/ragChat';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import CodeBlock from '../components/CodeBlock';
import {ChevronLeft, ChevronRight, Edit, MessageSquare, Pin, Plus, Square, Trash2,} from 'lucide-react';

interface KnowledgeBaseQueryPageProps {
  onBack: () => void;
  onUpload: () => void;
}

interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  evidence?: RagChatEvidence[];
  citationValidation?: CitationValidationReport | null;
  requestKey?: string;
  generationState?: RagDisplayState | null;
  statusMessage?: string;
}

interface CategoryGroup {
  name: string;
  items: KnowledgeBaseItem[];
  isExpanded: boolean;
}

export default function KnowledgeBaseQueryPage({ onBack, onUpload }: KnowledgeBaseQueryPageProps) {
  // 知识库状态
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [selectedKbIds, setSelectedKbIds] = useState<Set<number>>(new Set());
  const [loadingList, setLoadingList] = useState(true);

  // 搜索和排序状态
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(new Set(['未分类']));

  // 右侧面板状态
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  // 会话状态
  const [sessions, setSessions] = useState<RagChatSessionListItem[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<number | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState<string>('');
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [sessionDeleteConfirm, setSessionDeleteConfirm] = useState<{ id: number; title: string } | null>(null);
  const [editingSessionTitle, setEditingSessionTitle] = useState<{ id: number; title: string } | null>(null);
  const [newSessionTitle, setNewSessionTitle] = useState('');

  // 消息状态
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  const [expandedSourceMessages, setExpandedSourceMessages] = useState<Set<string>>(new Set());

  // refs
  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const rafRef = useRef<number>();
  const activeRequest = useRef<{key: string; controller: AbortController; sessionId?: number;
    messageId?: number; terminal: boolean; content: string; stopping: boolean} | null>(null);
  const viewVersion = useRef(0);

  const releaseRequest = () => {
    const active = activeRequest.current;
    activeRequest.current = null;
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    if (active && !active.terminal && active.sessionId && active.messageId) {
      void ragChatApi.cancelMessage(active.sessionId, active.messageId)
        .catch(error => console.error('停止旧会话生成失败', error));
    }
    active?.controller.abort();
  };

  useEffect(() => () => { viewVersion.current++; releaseRequest(); }, []);

  useEffect(() => {
    loadKnowledgeBases();
    loadSessions();
  }, []);

  useEffect(() => {
    if (!searchKeyword) {
      loadKnowledgeBases();
    }
  }, [sortBy]);

  const loadKnowledgeBases = async () => {
    setLoadingList(true);
    try {
      // 问答助手只显示向量化完成的知识库
      const list = await knowledgeBaseApi.getAllKnowledgeBases(sortBy, 'COMPLETED');
      setKnowledgeBases(list);
    } catch (err) {
      console.error('加载知识库列表失败', err);
    } finally {
      setLoadingList(false);
    }
  };

  const handleSearch = async () => {
    if (!searchKeyword.trim()) {
      loadKnowledgeBases();
      return;
    }
    setLoadingList(true);
    try {
      const list = await knowledgeBaseApi.search(searchKeyword.trim());
      setKnowledgeBases(list);
    } catch (err) {
      console.error('搜索知识库失败', err);
    } finally {
      setLoadingList(false);
    }
  };

  const groupedKnowledgeBases = useMemo((): CategoryGroup[] => {
    const groups: Map<string, KnowledgeBaseItem[]> = new Map();

    knowledgeBases.forEach(kb => {
      const category = kb.category || '未分类';
      if (!groups.has(category)) {
        groups.set(category, []);
      }
      groups.get(category)!.push(kb);
    });

    const result: CategoryGroup[] = [];
    const sortedCategories = Array.from(groups.keys()).sort((a, b) => {
      if (a === '未分类') return 1;
      if (b === '未分类') return -1;
      return a.localeCompare(b);
    });

    sortedCategories.forEach(name => {
      result.push({
        name,
        items: groups.get(name)!,
        isExpanded: expandedCategories.has(name),
      });
    });

    return result;
  }, [knowledgeBases, expandedCategories]);

  const toggleCategory = (category: string) => {
    setExpandedCategories(prev => {
      const next = new Set(prev);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  const loadSessions = async () => {
    setLoadingSessions(true);
    try {
      const list = await ragChatApi.listSessions();
      setSessions(list);
    } catch (err) {
      console.error('加载会话列表失败', err);
    } finally {
      setLoadingSessions(false);
    }
  };

  const handleToggleKb = (kbId: number) => {
    viewVersion.current++;
    releaseRequest();
    setLoading(false);
    setSelectedKbIds(prev => {
      const newSet = new Set(prev);
      if (newSet.has(kbId)) {
        newSet.delete(kbId);
      } else {
        newSet.add(kbId);
      }
      if (newSet.size !== prev.size && currentSessionId) {
        setCurrentSessionId(null);
        setCurrentSessionTitle('');
        setMessages([]);
      }
      return newSet;
    });
  };

  const handleNewSession = () => {
    viewVersion.current++;
    releaseRequest();
    setLoading(false);
    setCurrentSessionId(null);
    setCurrentSessionTitle('');
    setMessages([]);
  };

  const handleLoadSession = async (sessionId: number) => {
    const version = ++viewVersion.current;
    releaseRequest();
    setLoading(false);
    try {
      const detail = await ragChatApi.getSessionDetail(sessionId);
      if (version !== viewVersion.current) return;
      setCurrentSessionId(detail.id);
      setCurrentSessionTitle(detail.title);
      setSelectedKbIds(new Set(detail.knowledgeBases.map(kb => kb.id)));
      setMessages(detail.messages.map(m => ({
        id: m.id,
        type: m.type,
        content: m.content,
        timestamp: new Date(m.createdAt),
        evidence: m.evidence || [],
        citationValidation: m.citationValidation,
        generationState: m.generationState ?? (m.completed === false ? 'LEGACY_INCOMPLETE' : null),
      })));
    } catch (err) {
      console.error('加载会话失败', err);
    }
  };

  const handleDeleteSession = async () => {
    if (!sessionDeleteConfirm) return;
    try {
      await ragChatApi.deleteSession(sessionDeleteConfirm.id);
      await loadSessions();
      if (currentSessionId === sessionDeleteConfirm.id) {
        handleNewSession();
      }
      setSessionDeleteConfirm(null);
    } catch (err) {
      console.error('删除会话失败', err);
    }
  };

  const handleEditSessionTitle = (sessionId: number, currentTitle: string) => {
    setEditingSessionTitle({ id: sessionId, title: currentTitle });
    setNewSessionTitle(currentTitle);
  };

  const handleSaveSessionTitle = async () => {
    if (!editingSessionTitle || !newSessionTitle.trim()) return;
    try {
      await ragChatApi.updateSessionTitle(editingSessionTitle.id, newSessionTitle.trim());
      await loadSessions();
      if (currentSessionId === editingSessionTitle.id) {
        setCurrentSessionTitle(newSessionTitle.trim());
      }
      setEditingSessionTitle(null);
      setNewSessionTitle('');
    } catch (err) {
      console.error('更新会话标题失败', err);
    }
  };

  const handleTogglePin = async (sessionId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await ragChatApi.togglePin(sessionId);
      await loadSessions();
    } catch (err) {
      console.error('切换置顶状态失败', err);
    }
  };

  const formatMarkdown = (text: string): string => {
    if (!text) return '';
    return text
      // 确保标题 # 后有空格
      .replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')
      // 确保有序列表数字后有空格（如 1.xxx -> 1. xxx）
      .replace(/^(\s*)(\d+)\.([^\s\n])/gm, '$1$2. $3')
      // 确保无序列表 - 或 * 后有空格
      .replace(/^(\s*[-*])([^\s\n-])/gm, '$1 $2')
      // 压缩多余空行
      .replace(/\n{3,}/g, '\n\n');
  };

  const handleSubmitQuestion = async () => {
    if (!question.trim() || selectedKbIds.size === 0 || loading) return;

    const userQuestion = question.trim();
    const version = ++viewVersion.current;
    const active = {key: crypto.randomUUID(), controller: new AbortController(),
      sessionId: currentSessionId ?? undefined, messageId: undefined as number | undefined,
      terminal: false, content: '', stopping: false};
    activeRequest.current = active;
    const isCurrent = () => activeRequest.current === active && viewVersion.current === version;
    setQuestion('');
    setLoading(true);

    let sessionId = currentSessionId;
    if (!sessionId) {
      try {
        const session = await ragChatApi.createSession(Array.from(selectedKbIds));
        if (!isCurrent() || active.controller.signal.aborted) return;
        sessionId = session.id;
        active.sessionId = session.id;
        setCurrentSessionId(sessionId);
        setCurrentSessionTitle(session.title);
      } catch (err) {
        if (!isCurrent()) return;
        console.error('创建会话失败', err);
        setLoading(false);
        activeRequest.current = null;
        return;
      }
    }

    const userMessage: Message = {
      type: 'user',
      content: userQuestion,
      timestamp: new Date(),
    };
    setMessages(prev => [...prev, userMessage]);

    const assistantMessage: Message = {
      type: 'assistant',
      content: '',
      timestamp: new Date(),
      requestKey: active.key,
      generationState: 'GENERATING',
    };
    setMessages(prev => [...prev, assistantMessage]);

    const updateAssistantMessage = (changes: Partial<Message>) => {
      if (!isCurrent()) return;
      setMessages(prev => isCurrent() ? prev.map(item => item.requestKey === active.key
        ? {...item, ...changes} : item) : prev);
    };
    const end = (event: RagStreamEvent) => {
      if (!isCurrent() || active.terminal) return;
      active.terminal = true;
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      updateAssistantMessage({content: active.content, generationState: event.generationState,
        statusMessage: event.message ?? undefined});
      setLoading(false);
      void loadSessions();
      // 只刷新来源/状态；保存失败保留本页内容，避免用空占位覆盖它。
      if (event.errorCode !== 'SAVE_FAILED') {
        void ragChatApi.getSessionDetail(sessionId!).then(detail => {
          if (!isCurrent()) return;
          const saved = detail.messages.find(item => item.id === active.messageId);
          if (saved) updateAssistantMessage({evidence: saved.evidence || [], citationValidation: saved.citationValidation});
        }).catch(error => console.error('加载回答来源失败', error));
      }
    };
    await ragChatApi.sendMessageStream(sessionId, userQuestion, {
      signal: active.controller.signal,
      onStart: messageId => { active.messageId = messageId; updateAssistantMessage({id: messageId}); },
      onDelta: chunk => {
        if (!isCurrent() || active.terminal) return;
        active.content += chunk;
        if (rafRef.current) cancelAnimationFrame(rafRef.current);
        rafRef.current = requestAnimationFrame(() => updateAssistantMessage({content: active.content}));
      },
      onTerminal: end,
      onError: error => {
        if (!isCurrent() || active.terminal || active.stopping) return;
        if (rafRef.current) cancelAnimationFrame(rafRef.current);
        updateAssistantMessage({content: active.content, generationState: 'INTERRUPTED', statusMessage: error.message});
        setLoading(false);
        active.terminal = true;
        if (active.messageId) void ragChatApi.cancelMessage(sessionId!, active.messageId)
          .catch(failure => console.error('清理中断生成失败', failure));
      },
    });
  };

  const handleStopGeneration = async () => {
    const active = activeRequest.current;
    if (!active || active.terminal || active.stopping) return;
    active.stopping = true;
    try {
      if (!active.sessionId || !active.messageId) {
        throw new Error('连接尚未建立，已停止等待；服务端会在断连或超时后结束生成');
      }
      const event = await ragChatApi.cancelMessage(active.sessionId, active.messageId);
      if (activeRequest.current !== active) return;
      active.terminal = true;
      setMessages(prev => prev.map(item => item.requestKey === active.key ? {...item, content: active.content,
        generationState: event.generationState, statusMessage: event.message ?? undefined} : item));
    } catch (error) {
      if (activeRequest.current !== active) return;
      setMessages(prev => prev.map(item => item.requestKey === active.key ? {...item, content: active.content,
        generationState: 'INTERRUPTED', statusMessage: error instanceof Error ? error.message : '停止失败，请刷新确认'} : item));
    } finally {
      active.controller.abort();
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      if (activeRequest.current === active) { setLoading(false); active.terminal = true; void loadSessions(); }
    }
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const formatTimeAgo = (dateStr: string): string => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;
    return formatDateOnly(dateStr);
  };

  return (
    <div className="max-w-7xl mx-auto pb-10">
      {/* 头部 */}
      <div className="flex items-center justify-between mb-10">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold text-primary-600 dark:text-primary-400 mb-3">
            <MessageSquare className="w-4 h-4" />
            Rehevo Context
          </div>
          <h1 className="text-4xl font-bold text-slate-950 dark:text-white">问答助手</h1>
          <p className="text-slate-500 dark:text-slate-400 mt-3">选定材料后，让回答始终回到可追溯的上下文。</p>
        </div>
        <div className="flex gap-3">
          <motion.button
            onClick={onUpload}
            className="px-4 py-2.5 border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-800 transition-all text-sm"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            上传知识库
          </motion.button>
          <motion.button
            onClick={onBack}
            className="px-4 py-2.5 border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-800 transition-all text-sm"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            返回
          </motion.button>
        </div>
      </div>

      <div className="flex gap-4 h-[calc(100vh-10rem)]">
        {/* 左侧：对话历史 */}
        <div className="w-64 flex-shrink-0">
          <div
              className="bg-white dark:bg-slate-900 rounded-[24px] p-4 shadow-sm h-full flex flex-col border border-slate-200/80 dark:border-slate-800">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-slate-800 dark:text-white">对话历史</h2>
              <motion.button
                onClick={handleNewSession}
                disabled={selectedKbIds.size === 0}
                className="p-1.5 text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                title="新建对话"
              >
                <Plus className="w-5 h-5" />
              </motion.button>
            </div>

            <div className="flex-1 overflow-y-auto">
              {loadingSessions ? (
                <div className="text-center py-6">
                  <motion.div
                    className="w-5 h-5 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
                    animate={{ rotate: 360 }}
                    transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                  />
                </div>
              ) : sessions.length === 0 ? (
                  <div className="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
                  暂无对话历史
                </div>
              ) : (
                <div className="space-y-2">
                  {sessions.map((session) => (
                    <div
                      key={session.id}
                      onClick={() => handleLoadSession(session.id)}
                      className={`p-3 rounded-lg cursor-pointer transition-all group ${currentSessionId === session.id
                          ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-500'
                          : 'bg-slate-50 dark:bg-slate-700/50 hover:bg-slate-100 dark:hover:bg-slate-700 border border-transparent'
                        } ${session.isPinned ? 'border-l-4 border-l-primary-500' : ''}`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-1.5">
                            {session.isPinned && (
                              <Pin className="w-3.5 h-3.5 text-primary-500 fill-primary-500 flex-shrink-0" />
                            )}
                            <p className="font-medium text-slate-800 dark:text-white text-sm truncate">{session.title}</p>
                          </div>
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                            {session.messageCount} 条消息 · {formatTimeAgo(session.updatedAt)}
                          </p>
                        </div>
                        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-all">
                          <button
                            onClick={(e) => handleTogglePin(session.id, e)}
                            className={`p-1 rounded transition-colors ${session.isPinned
                              ? 'text-primary-500 hover:text-primary-600'
                              : 'text-slate-400 hover:text-primary-500'
                              }`}
                            title={session.isPinned ? '取消置顶' : '置顶'}
                          >
                            <Pin className={`w-4 h-4 ${session.isPinned ? 'fill-primary-500' : ''}`} />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleEditSessionTitle(session.id, session.title);
                            }}
                            className="p-1 text-slate-400 hover:text-primary-500 rounded transition-colors"
                            title="编辑标题"
                          >
                            <Edit className="w-4 h-4" />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSessionDeleteConfirm({ id: session.id, title: session.title });
                            }}
                            className="p-1 text-slate-400 hover:text-red-500 rounded transition-colors"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 中间：聊天区域 */}
        <div className="flex-1 min-w-0">
          <div
              className="bg-white dark:bg-slate-900 rounded-[24px] shadow-sm flex flex-col h-full border border-slate-200/80 dark:border-slate-800">
            {selectedKbIds.size > 0 ? (
              <>
                {/* 会话信息 */}
                <div className="p-4 border-b border-slate-200 dark:border-slate-600">
                  <h2 className="text-base font-semibold text-slate-800 dark:text-white">
                    {currentSessionTitle || (selectedKbIds.size === 1
                      ? knowledgeBases.find(kb => kb.id === Array.from(selectedKbIds)[0])?.name || '新对话'
                      : `${selectedKbIds.size} 个知识库 - 新对话`)}
                  </h2>
                  <div className="flex flex-wrap gap-1.5 mt-2">
                    {Array.from(selectedKbIds).map(kbId => {
                      const kb = knowledgeBases.find(k => k.id === kbId);
                      return kb ? (
                          <span key={kbId}
                                className="px-2 py-0.5 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-xs rounded-full">
                          {kb.name}
                        </span>
                      ) : null;
                    })}
                  </div>
                </div>

                {/* 消息列表 */}
                <div className="flex-1 min-h-0 relative dark:bg-slate-800">
                  {messages.length === 0 ? (
                      <div
                          className="absolute inset-0 flex flex-col items-center justify-center text-slate-400 dark:text-slate-500">
                      <MessageSquare className="w-12 h-12 mx-auto mb-3 opacity-50" />
                      <p className="text-sm">开始提问吧！</p>
                    </div>
                  ) : (
                    <Virtuoso
                      ref={virtuosoRef}
                      data={messages}
                      initialTopMostItemIndex={messages.length - 1}
                      followOutput={loading ? 'smooth' : false}
                      className="h-full w-full"
                      itemContent={(index, msg) => (
                          <div className="pb-4 px-4 first:pt-4 dark:bg-slate-800">
                          <motion.div
                            initial={{ opacity: 0, y: 10 }}
                            animate={{ opacity: 1, y: 0 }}
                            className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                          >
                            <div
                              className={`max-w-[85%] rounded-2xl p-4 shadow-sm ${msg.type === 'user'
                                ? 'bg-primary-600 text-white'
                                  : 'bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-600 text-slate-800 dark:text-slate-100'
                              }`}
                            >
                              {msg.type === 'user' ? (
                                <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
                              ) : (
                                  <div className="prose prose-slate dark:prose-invert prose-sm max-w-none">
                                  <ReactMarkdown
                                    remarkPlugins={[remarkGfm, [remarkRagCitations, {
                                      evidenceIds: (msg.evidence || []).flatMap(e => e.evidenceId ? [e.evidenceId] : []),
                                      scope: String(msg.id ?? `pending-${index}`),
                                    }]]}
                                    components={{
                                      a: ({href, children, title}) => {
                                        const sourceId = (msg.evidence || []).find(e => e.evidenceId &&
                                          href === `#${evidenceAnchor(String(msg.id ?? `pending-${index}`), e.evidenceId)}`);
                                        if (!sourceId) return <a href={href} title={title}>{children}</a>;
                                        return <a href={href} title={title} onClick={event => {
                                          event.preventDefault();
                                          const scope = String(msg.id ?? `pending-${index}`);
                                          setExpandedSourceMessages(previous => new Set(previous).add(scope));
                                          requestAnimationFrame(() => {
                                            document.getElementById(href!.slice(1))?.scrollIntoView({block: 'nearest'});
                                          });
                                        }}>{children}</a>;
                                      },
                                      // 自定义代码块渲染
                                      code: ({ className, children }) => {
                                        const match = /language-(\w+)/.exec(className || '');
                                        const isInline = !match;

                                        if (isInline) {
                                          return (
                                              <code
                                                  className="bg-slate-100 dark:bg-slate-600 text-primary-600 dark:text-primary-400 px-1.5 py-0.5 rounded-md text-sm font-normal">
                                              {children}
                                            </code>
                                          );
                                        }

                                        // 代码块使用 CodeBlock 组件
                                        return (
                                          <CodeBlock language={match[1]}>
                                            {String(children).replace(/\n$/, '')}
                                          </CodeBlock>
                                        );
                                      },
                                      // 禁用默认 pre 渲染，由 CodeBlock 处理
                                      pre: ({ children }) => <>{children}</>,
                                    }}
                                  >
                                    {formatMarkdown(msg.content)}
                                  </ReactMarkdown>
                                  {msg.generationState && msg.generationState !== 'COMPLETED' && (
                                    <p role="status" className="mt-3 text-xs text-amber-700 dark:text-amber-300 not-prose">
                                      {msg.generationState === 'GENERATING' ? '正在生成…' :
                                        msg.generationState === 'CANCELLED' ? '已停止，回答未完成' :
                                        msg.generationState === 'FAILED' ? (msg.statusMessage || '生成失败，回答未完成') :
                                        msg.generationState === 'LEGACY_INCOMPLETE' ? '历史回答未完成' :
                                        (msg.statusMessage || '连接中断，回答未完成')}
                                    </p>
                                  )}
                                  {msg.evidence && msg.evidence.length > 0 && !loading && (
                                    <details open={expandedSourceMessages.has(String(msg.id ?? `pending-${index}`))}
                                      onToggle={event => {
                                        const open = event.currentTarget.open;
                                        const scope = String(msg.id ?? `pending-${index}`);
                                        setExpandedSourceMessages(previous => {
                                          if (previous.has(scope) === open) return previous;
                                          const next = new Set(previous);
                                          if (open) next.add(scope); else next.delete(scope);
                                          return next;
                                        });
                                      }}
                                      className="mt-4 rounded-xl border border-primary-100 dark:border-primary-900/50 bg-primary-50/50 dark:bg-primary-950/20 not-prose">
                                      <summary className="cursor-pointer px-3 py-2 text-xs font-semibold text-primary-700 dark:text-primary-300">
                                        检索来源（{msg.evidence.length} 个片段）
                                      </summary>
                                      <div className="space-y-2 px-3 pb-3">
                                        {msg.evidence.map((evidence, evidenceIndex) => (
                                          <div key={`${evidence.documentSha256}-${evidence.chunkIndex}-${evidenceIndex}`}
                                            id={evidence.evidenceId ? evidenceAnchor(String(msg.id ?? `pending-${index}`), evidence.evidenceId) : undefined}
                                            className="rounded-lg bg-white/80 dark:bg-slate-900/70 p-2.5 text-xs text-slate-600 dark:text-slate-300">
                                            <div className="mb-1 flex flex-wrap gap-x-2 text-slate-500 dark:text-slate-400">
                                              <span>{evidence.evidenceId ? `[${evidence.evidenceId}]` : `来源 ${evidenceIndex + 1}`}</span>
                                              {evidence.chunkIndex !== null && <span>片段 {evidence.chunkIndex + 1}</span>}
                                              {evidence.originalFilename && <span>{evidence.originalFilename}</span>}
                                              {evidence.contentType && <span>{evidence.contentType}</span>}
                                              {evidence.retrievalSources.length > 0 && <span>{evidence.retrievalSources.join(' + ')}</span>}
                                            </div>
                                            <p className="whitespace-pre-wrap leading-5">{evidence.contentPreview || '该历史回答没有可展示的片段预览。'}</p>
                                          </div>
                                        ))}
                                      </div>
                                    </details>
                                  )}
                                  {msg.citationValidation?.status === 'UNKNOWN_REFERENCES' && (
                                    <p className="mt-2 text-xs text-amber-700 dark:text-amber-400 not-prose">
                                      部分引用未匹配到本轮来源：{msg.citationValidation.unknownEvidenceIds.map(id => `[${id}]`).join('、')}
                                    </p>
                                  )}
                                  {loading && index === messages.length - 1 && (
                                    <span className="inline-block w-0.5 h-5 bg-primary-500 ml-1 animate-pulse" />
                                  )}
                                </div>
                              )}
                            </div>
                          </motion.div>
                        </div>
                      )}
                    />
                  )}
                </div>

                {/* 输入区域 */}
                <div className="p-4 border-t border-slate-200 dark:border-slate-600">
                  <div className="flex gap-3">
                    <input
                      type="text"
                      value={question}
                      onChange={(e) => setQuestion(e.target.value)}
                      onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSubmitQuestion()}
                      placeholder="输入您的问题..."
                      className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                      disabled={loading}
                    />
                    {loading ? <button onClick={handleStopGeneration}
                      className="px-5 py-2.5 rounded-xl border border-slate-300 dark:border-slate-600 text-sm flex items-center gap-2">
                      <Square className="w-4 h-4" />停止生成
                    </button> : <motion.button
                      onClick={handleSubmitQuestion}
                      disabled={!question.trim() || selectedKbIds.size === 0 || loading}
                      className="px-5 py-2.5 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                      whileHover={{ scale: loading ? 1 : 1.02 }}
                      whileTap={{ scale: loading ? 1 : 0.98 }}
                    >
                      发送
                    </motion.button>}
                  </div>
                </div>
              </>
            ) : (
                <div className="flex-1 flex items-center justify-center text-slate-400 dark:text-slate-500">
                <div className="text-center">
                  <svg className="w-12 h-12 mx-auto mb-3 opacity-50" viewBox="0 0 24 24" fill="none">
                    <path d="M21 15C21 15.5304 20.7893 16.0391 20.4142 16.4142C20.0391 16.7893 19.5304 17 19 17H7L3 21V5C3 4.46957 3.21071 3.96086 3.58579 3.58579C3.96086 3.21071 4.46957 3 5 3H19C19.5304 3 20.0391 3.21071 20.4142 3.58579C20.7893 3.96086 21 4.46957 21 5V15Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <p className="text-sm">请先在右侧选择知识库</p>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* 右侧：知识库选择（简化版） */}
        <AnimatePresence>
          {rightPanelOpen && (
            <motion.div
              initial={{ width: 0, opacity: 0 }}
              animate={{ width: 280, opacity: 1 }}
              exit={{ width: 0, opacity: 0 }}
              transition={{ duration: 0.2 }}
              className="flex-shrink-0 overflow-hidden"
            >
              <div
                  className="bg-white dark:bg-slate-800 rounded-2xl p-4 shadow-sm h-full flex flex-col w-[280px] border border-slate-100 dark:border-slate-700">
                <div className="flex items-center justify-between mb-4">
                  <h2 className="text-base font-semibold text-slate-800 dark:text-white">选择知识库</h2>
                  <button
                    onClick={() => setRightPanelOpen(false)}
                    className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded"
                  >
                    <ChevronLeft className="w-5 h-5" />
                  </button>
                </div>

                {/* 搜索框 */}
                <div className="flex gap-2 mb-3">
                  <input
                    type="text"
                    value={searchKeyword}
                    onChange={(e) => setSearchKeyword(e.target.value)}
                    onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
                    placeholder="搜索..."
                    className="flex-1 px-3 py-1.5 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  />
                  <button
                    onClick={handleSearch}
                    className="px-3 py-1.5 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600"
                  >
                    搜索
                  </button>
                </div>

                {/* 排序 */}
                <div className="mb-3">
                  <select
                    value={sortBy}
                    onChange={(e) => {
                      setSortBy(e.target.value as SortOption);
                      setSearchKeyword('');
                    }}
                    className="w-full px-2 py-1 text-xs border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-700 dark:text-slate-300"
                  >
                    <option value="time">时间排序</option>
                    <option value="size">大小排序</option>
                    <option value="access">访问排序</option>
                    <option value="question">提问排序</option>
                  </select>
                </div>

                {/* 知识库列表 */}
                <div className="flex-1 overflow-y-auto">
                  {loadingList ? (
                    <div className="text-center py-6">
                      <motion.div
                        className="w-5 h-5 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
                        animate={{ rotate: 360 }}
                        transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                      />
                    </div>
                  ) : knowledgeBases.length === 0 ? (
                      <div className="text-center py-6 text-slate-500 dark:text-slate-400">
                      <p className="mb-2 text-sm">{searchKeyword ? '未找到' : '暂无知识库'}</p>
                      {!searchKeyword && (
                        <button onClick={onUpload} className="text-primary-500 hover:text-primary-600 font-medium text-sm">
                          立即上传
                        </button>
                      )}
                    </div>
                  ) : (
                    <div className="space-y-2">
                      {groupedKnowledgeBases.map((group) => (
                          <div key={group.name}
                               className="border border-slate-100 dark:border-slate-700 rounded-lg overflow-hidden">
                          <button
                            onClick={() => toggleCategory(group.name)}
                            className="w-full flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-slate-700/50 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                          >
                            <div className="flex items-center gap-2">
                              <ChevronRight
                                className={`w-3.5 h-3.5 text-slate-400 transition-transform ${group.isExpanded ? 'rotate-90' : ''}`}
                              />
                              <span
                                  className="font-medium text-slate-700 dark:text-slate-300 text-sm">{group.name}</span>
                            </div>
                            <span className="text-xs text-slate-400">{group.items.length}</span>
                          </button>

                          <AnimatePresence>
                            {group.isExpanded && (
                              <motion.div
                                initial={{ height: 0, opacity: 0 }}
                                animate={{ height: 'auto', opacity: 1 }}
                                exit={{ height: 0, opacity: 0 }}
                                transition={{ duration: 0.2 }}
                                className="overflow-hidden"
                              >
                                <div className="p-2 space-y-1">
                                  {group.items.map((kb) => (
                                    <div
                                      key={kb.id}
                                      onClick={() => handleToggleKb(kb.id)}
                                      className={`p-2 rounded-lg cursor-pointer transition-all ${selectedKbIds.has(kb.id)
                                          ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-500'
                                          : 'bg-white dark:bg-slate-700/50 hover:bg-slate-50 dark:hover:bg-slate-700 border border-transparent'
                                        }`}
                                    >
                                      <div className="flex items-center gap-2">
                                        <input
                                          type="checkbox"
                                          checked={selectedKbIds.has(kb.id)}
                                          onChange={() => handleToggleKb(kb.id)}
                                          onClick={(e) => e.stopPropagation()}
                                          className="w-3.5 h-3.5 text-primary-500 rounded focus:ring-primary-500"
                                        />
                                        <span
                                            className="font-medium text-slate-800 dark:text-white text-xs truncate flex-1">{kb.name}</span>
                                      </div>
                                      <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 ml-5">{formatFileSize(kb.fileSize)}</p>
                                    </div>
                                  ))}
                                </div>
                              </motion.div>
                            )}
                          </AnimatePresence>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* 收起状态下的展开按钮 */}
        {!rightPanelOpen && (
          <button
            onClick={() => setRightPanelOpen(true)}
            className="flex-shrink-0 w-10 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 flex items-center justify-center hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            title="展开知识库面板"
          >
            <ChevronRight className="w-5 h-5 text-slate-400" />
          </button>
        )}
      </div>

      {/* 删除会话确认弹窗 */}
      <DeleteConfirmDialog
        open={!!sessionDeleteConfirm}
        item={sessionDeleteConfirm ? { id: 0, title: sessionDeleteConfirm.title } : null}
        itemType="对话"
        onConfirm={handleDeleteSession}
        onCancel={() => setSessionDeleteConfirm(null)}
      />

      {/* 编辑会话标题弹窗 */}
      <AnimatePresence>
        {editingSessionTitle && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => {
                setEditingSessionTitle(null);
                setNewSessionTitle('');
              }}
              className="fixed inset-0 z-50 bg-slate-950/45 backdrop-blur-sm"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="w-full max-w-md rounded-[28px] border border-slate-200/80 bg-white p-7 shadow-2xl dark:border-slate-800 dark:bg-slate-900"
              >
                <h3 className="mb-2 text-xl font-bold tracking-tight text-slate-900 dark:text-white">编辑标题</h3>
                <p className="mb-5 text-sm text-slate-500 dark:text-slate-400">为这次资料问答留下一个更容易回看的名称。</p>
                <input
                  type="text"
                  value={newSessionTitle}
                  onChange={(e) => setNewSessionTitle(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && handleSaveSessionTitle()}
                  placeholder="请输入新标题"
                  className="mb-5 w-full rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-900 placeholder-slate-400 focus:border-primary-500 focus:outline-none focus:ring-2 focus:ring-primary-500/20 dark:border-slate-700 dark:bg-slate-950/30 dark:text-white"
                  autoFocus
                />
                <div className="flex justify-end gap-3">
                  <button
                    onClick={() => {
                      setEditingSessionTitle(null);
                      setNewSessionTitle('');
                    }}
                    className="rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSaveSessionTitle}
                    disabled={!newSessionTitle.trim()}
                    className="rounded-xl bg-primary-600 px-4 py-2 text-sm font-medium text-white shadow-md shadow-primary-600/20 transition-colors hover:bg-primary-700 disabled:opacity-50"
                  >
                    保存
                  </button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>
    </div>
  );
}
