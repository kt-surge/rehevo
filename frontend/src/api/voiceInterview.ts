import { VoiceInterviewWebSocket } from '../utils/voiceInterviewSocket';
import { API_BASE_URL, request } from './request';
import type { VoiceAudioFrame } from '../types/voiceAudio';

// ========== 类型定义 ==========

export interface CreateSessionRequest {
  roleType?: string;
  skillId: string;
  difficulty?: string;
  customJdText?: string;
  resumeId?: number;
  introEnabled?: boolean;
  techEnabled?: boolean;
  projectEnabled?: boolean;
  hrEnabled?: boolean;
  plannedDuration?: number;
  llmProvider?: string;
}

export interface SessionResponse {
  sessionId: number;
  roleType: string;
  currentPhase: string;
  status: string;
  startTime: string;
  plannedDuration: number;
  webSocketUrl: string;
}

export interface InterviewMessage {
  id: number;
  sessionId: number;
  messageType: string;
  phase: string;
  userRecognizedText: string;
  aiGeneratedText: string;
  timestamp: string;
  sequenceNum: number;
}

export interface VoiceAnswerDetail {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  evaluationStatus: 'SCORED' | 'UNANSWERED' | 'EVALUATION_FAILED';
  rubricLevel: number;
  answerEvidence: string[];
  missingPoints: string[];
  factualRisks: string[];
  nextAction: string;
  referenceAnswer?: string | null;
  keyPoints?: string[] | null;
}

export interface VoiceEvaluationDetail {
  sessionId: number;
  totalQuestions: number;
  answeredQuestions: number;
  scoredQuestions: number;
  failedQuestions: number;
  evidenceSupportedQuestions: number;
  evaluationCoverage: number;
  evidenceCoverage: number;
  overallScore: number;
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  trainingTasks: VoiceTrainingTask[];
  answers: VoiceAnswerDetail[];
}

export interface VoiceTrainingTask {
  competency: string;
  questionIndexes: number[];
  reason: string;
  action: string;
  completionCriteria: string;
  priority: number;
}

/**
 * Evaluation status response from GET/POST evaluation endpoints
 */
export interface EvaluationStatusResponse {
  evaluateStatus: string | null;  // PENDING | PROCESSING | COMPLETED | FAILED
  evaluateError?: string | null;
  evaluation?: VoiceEvaluationDetail | null;
}

/**
 * Session metadata for history list
 */
export interface SessionMeta {
  sessionId: number;
  roleType: string;
  status: string;
  currentPhase: string;
  createdAt: string;
  updatedAt: string;
  actualDuration?: number;
  messageCount: number;
  evaluateStatus?: string;
  evaluateError?: string;
}

// WebSocket 消息类型
export interface WebSocketAudioMessage {
  type: 'audio';
  data: string; // Base64 编码的音频
  timestamp?: number;
}

export interface WebSocketSubtitleMessage {
  type: 'subtitle';
  text: string;
  isFinal: boolean;
}

export interface WebSocketTurnMetadata {
  sessionId?: string;
  turnId?: string;
  eventId?: string;
  sequence?: number;
  eventType?: string;
  createdAt?: number;
  turnPhase?: 'THINKING' | 'SPEAKING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
}

export interface WebSocketAudioResponseMessage extends WebSocketTurnMetadata {
  type: 'audio';
  data: string; // Base64 编码的音频
  text: string;
}

export interface WebSocketTextMessage extends WebSocketTurnMetadata {
  type: 'text';
  content: string;
  final?: boolean;
}

export interface WebSocketAudioChunkMessage extends WebSocketTurnMetadata {
  type: 'audio_chunk';
  data: string; // Base64 WAV
  index: number;
  isLast: boolean;
}

export interface WebSocketControlResponseMessage extends WebSocketTurnMetadata {
  type: 'control';
  action: string;
  message?: string;
  timestamp?: number;
  cancelRequestId?: string;
  clientRequestId?: string;
}

export interface WebSocketErrorMessage {
  type: 'error';
  message: string;
}

export type WebSocketMessage =
  | WebSocketAudioMessage
  | WebSocketSubtitleMessage
  | WebSocketAudioResponseMessage
  | WebSocketTextMessage
  | WebSocketAudioChunkMessage
  | VoiceAudioFrame
  | WebSocketControlResponseMessage
  | WebSocketErrorMessage;

// WebSocket 事件处理器
export interface WebSocketEventHandlers {
  onMessage?: (message: WebSocketMessage) => void;
  onSubtitle?: (text: string, isFinal: boolean) => void;
  onAudioResponse?: (audioData: string, text: string, turnId?: string) => void;
  onTextResponse?: (text: string, isFinal: boolean, turnId?: string) => void;
  onAudioChunk?: (data: string, index: number, isLast: boolean, turnId?: string) => void;
  onAudioFrame?: (frame: VoiceAudioFrame) => void;
  onControl?: (action: string, message?: string, turnId?: string, control?: WebSocketControlResponseMessage) => void;
  onErrorMessage?: (message: string) => void;
  onOpen?: () => void;
  onClose?: (event: CloseEvent) => void;
  onError?: (error: Event) => void;
}

// ========== API 函数 ==========

export const voiceInterviewApi = {
  /**
   * 创建新的语音面试会话
   */
  async createSession(data: CreateSessionRequest): Promise<SessionResponse> {
    return request.post<SessionResponse>('/api/voice-interview/sessions', data);
  },

  /**
   * 获取会话详情
   */
  async getSession(sessionId: number): Promise<SessionResponse> {
    return request.get<SessionResponse>(`/api/voice-interview/sessions/${sessionId}`);
  },

  /**
   * 结束会话
   */
  async endSession(sessionId: number): Promise<void> {
    return request.post<void>(`/api/voice-interview/sessions/${sessionId}/end`);
  },

  /**
   * 获取会话消息列表
   */
  async getMessages(sessionId: number): Promise<InterviewMessage[]> {
    return request.get<InterviewMessage[]>(
      `/api/voice-interview/sessions/${sessionId}/messages`
    );
  },

  /**
   * 获取面试评估状态和结果（轮询）
   */
  async getEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.get<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  /**
   * 触发异步评估生成
   */
  async generateEvaluation(sessionId: number): Promise<EvaluationStatusResponse> {
    return request.post<EvaluationStatusResponse>(
      `/api/voice-interview/sessions/${sessionId}/evaluation`
    );
  },

  /**
   * Pause interview session
   */
  async pauseSession(sessionId: number, reason: string = 'user_initiated'): Promise<void> {
    return request.put(
      `/api/voice-interview/sessions/${sessionId}/pause`,
      { reason }
    );
  },

  /**
   * Resume interview session
   */
  async resumeSession(sessionId: number): Promise<SessionResponse> {
    return request.put<SessionResponse>(
      `/api/voice-interview/sessions/${sessionId}/resume`
    );
  },

  /**
   * Get all sessions
   */
  async getAllSessions(userId?: string, status?: string): Promise<SessionMeta[]> {
    const params = new URLSearchParams();
    if (userId) params.append('userId', userId);
    if (status) params.append('status', status);

    return request.get<SessionMeta[]>(
      `/api/voice-interview/sessions?${params.toString()}`
    );
  },

  /**
   * 删除语音面试会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/voice-interview/sessions/${sessionId}`);
  },

};

// ========== WebSocket 连接管理类 ==========

export { VoiceInterviewWebSocket };

// ========== 便捷函数 ==========

/**
 * 创建并连接 WebSocket
 */
export function connectWebSocket(
  sessionId: number,
  webSocketUrl: string,
  handlers: WebSocketEventHandlers
): VoiceInterviewWebSocket {
  const apiOrigin = new URL(API_BASE_URL || '/', window.location.origin);
  const socketUrl = new URL(webSocketUrl, apiOrigin);
  if (socketUrl.protocol === 'https:') socketUrl.protocol = 'wss:';
  if (socketUrl.protocol === 'http:') socketUrl.protocol = 'ws:';
  if (socketUrl.protocol !== 'ws:' && socketUrl.protocol !== 'wss:') {
    throw new Error('语音连接地址协议无效');
  }
  const ws = new VoiceInterviewWebSocket(sessionId, socketUrl.href, handlers);
  ws.connect();
  return ws;
}

export default voiceInterviewApi;
