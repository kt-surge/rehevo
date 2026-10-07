import type { RagStreamCallbacks, RagStreamEvent } from '../types/ragChat';

/** 每个请求独立状态；EOF 不能替代明确的 terminal。 */
export function ragStreamProtocol(callbacks: RagStreamCallbacks) {
  let messageId: number | undefined;
  let terminal = false;
  return {
    onEvent(name: string, data: string) {
      const event = JSON.parse(data) as RagStreamEvent;
      if (event.event !== name || !Number.isSafeInteger(event.messageId) || event.messageId <= 0) {
        throw new Error('回答事件格式无效');
      }
      if (terminal) throw new Error('回答结束后收到额外事件');
      if (name === 'start' && messageId === undefined) {
        messageId = event.messageId;
        callbacks.onStart(messageId);
        return;
      }
      if (messageId === undefined || event.messageId !== messageId) {
        throw new Error('回答事件不属于当前请求');
      }
      if (name === 'heartbeat') return;
      if (name === 'delta' && typeof event.content === 'string') {
        callbacks.onDelta(event.content);
        return;
      }
      if (name === 'terminal' && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(event.generationState ?? '')) {
        terminal = true;
        callbacks.onTerminal(event);
        return;
      }
      throw new Error('回答事件顺序或状态无效');
    },
    onComplete() {
      if (!terminal) callbacks.onError(new Error('连接中断，回答未完成，已保留收到的内容'));
    },
  };
}
