import { request } from './request';
import { streamSse } from './stream';
import { ragStreamProtocol } from '../utils/ragStreamProtocol';
import type { RagChatSession, RagChatSessionListItem, RagChatSessionDetail,
  RagStreamCallbacks, RagStreamEvent } from '../types/ragChat';
export type { RagChatEvidence, RagChatSessionListItem } from '../types/ragChat';

// ========== API 函数 ==========

export const ragChatApi = {
  /**
   * 创建新会话
   */
  async createSession(knowledgeBaseIds: number[], title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/rag-chat/sessions', {
      knowledgeBaseIds,
      title,
    });
  },

  /**
   * 获取会话列表
   */
  async listSessions(): Promise<RagChatSessionListItem[]> {
    return request.get<RagChatSessionListItem[]>('/api/rag-chat/sessions');
  },

  /**
   * 获取会话详情
   */
  async getSessionDetail(sessionId: number): Promise<RagChatSessionDetail> {
    return request.get<RagChatSessionDetail>(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  async updateSessionTitle(sessionId: number, title: string): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/title`, { title });
  },

  /**
   * 更新会话知识库
   */
  async updateKnowledgeBases(sessionId: number, knowledgeBaseIds: number[]): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/knowledge-bases`, {
      knowledgeBaseIds,
    });
  },

  /**
   * 切换会话置顶状态
   */
  async togglePin(sessionId: number): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/pin`);
  },

  /**
   * 删除会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 发送消息（流式SSE）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    callbacks: RagStreamCallbacks
  ): Promise<void> {
    const protocol = ragStreamProtocol(callbacks);
    return streamSse({
      url: `/api/rag-chat/sessions/${sessionId}/messages/stream`,
      init: {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
        signal: callbacks.signal,
      },
      onMessage: () => {},
      onEvent: protocol.onEvent,
      onComplete: protocol.onComplete,
      onError: callbacks.onError,
      parseMode: 'event',
      trimDataPrefixSpace: true,
    });
  },

  async cancelMessage(sessionId: number, messageId: number): Promise<RagStreamEvent> {
    return request.post(`/api/rag-chat/sessions/${sessionId}/messages/${messageId}/cancel`);
  },
};
