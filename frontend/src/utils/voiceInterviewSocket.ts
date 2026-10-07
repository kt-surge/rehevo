import type { WebSocketMessage, WebSocketEventHandlers, WebSocketTurnMetadata, WebSocketSubtitleMessage, WebSocketAudioResponseMessage, WebSocketAudioChunkMessage, WebSocketTextMessage, WebSocketAudioMessage } from '../api/voiceInterview';

export class VoiceInterviewWebSocket {
  private ws: WebSocket | null = null;
  private url: string;
  private handlers: WebSocketEventHandlers;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 3;
  private reconnectDelay = 2000;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manuallyClosed = false;
  /**
   * 服务端为同一回复 Turn 分配单调 sequence。WebSocket 重连、TTS 回调和浏览器任务
   * 切换叠加时，旧帧即使晚到也不能重新触发播放或覆盖字幕。
   */
  private lastTurnSequence = new Map<string, number>();

  constructor(_sessionId: number, url: string, handlers: WebSocketEventHandlers) {
    this.url = url;
    this.handlers = handlers;
  }

  /**
   * 建立 WebSocket 连接
   */
  connect(): void {
    try {
      this.clearReconnectTimer();
      this.manuallyClosed = false;
      const previous = this.ws;
      this.ws = null;
      previous?.close(1000, 'Connection replaced');
      const socket = new WebSocket(this.url);
      this.ws = socket;

      socket.onopen = () => {
        if (this.ws !== socket || this.manuallyClosed) { return; }
        this.reconnectAttempts = 0;
        // 新连接对应服务端的新 TurnCoordinator，旧连接的 sequence 不能沿用。
        this.lastTurnSequence.clear();
        this.handlers.onOpen?.();
      };

      socket.onmessage = (event) => {
        if (this.ws !== socket || this.manuallyClosed) { return; }
        try {
          const message = JSON.parse(event.data) as WebSocketMessage;

          if (!this.shouldDispatchTurnEvent(message)) {
            return;
          }

          // 调用通用消息处理器
          this.handlers.onMessage?.(message);

          // 根据消息类型调用特定处理器
          switch (message.type) {
            case 'subtitle':
              this.handlers.onSubtitle?.(
                message.text,
                (message as WebSocketSubtitleMessage).isFinal
              );
              break;
            case 'audio':
              // 检查是否是 AI 响应（包含 text 字段）
              if ('text' in message) {
                const audioMsg = message as WebSocketAudioResponseMessage;
                this.handlers.onAudioResponse?.(audioMsg.data, audioMsg.text, audioMsg.turnId);
              }
              break;
            case 'audio_chunk':
              if ('index' in message) {
                const chunkMsg = message as WebSocketAudioChunkMessage;
                this.handlers.onAudioChunk?.(chunkMsg.data, chunkMsg.index, chunkMsg.isLast, chunkMsg.turnId);
              }
              break;
            case 'audio_frame':
              this.handlers.onAudioFrame?.(message);
              break;
            case 'text':
              if ('content' in message) {
                const textMsg = message as WebSocketTextMessage;
                this.handlers.onTextResponse?.(textMsg.content, !!textMsg.final, textMsg.turnId);
              }
              break;
            case 'control':
              this.handlers.onControl?.(message.action, message.message, message.turnId, message);
              break;
            case 'error':
              this.handlers.onErrorMessage?.(message.message);
              break;
          }
        } catch (error) {
          console.error('Error parsing WebSocket message:', error);
        }
      };

      socket.onclose = (event) => {
        if (this.ws !== socket || this.manuallyClosed) { return; }
        this.ws = null;
        this.handlers.onClose?.(event);

        const recoverable = [1001, 1005, 1006, 1011, 1012, 1013].includes(event.code);
        if (!this.manuallyClosed && recoverable && this.reconnectAttempts < this.maxReconnectAttempts) {
          this.reconnectAttempts++;
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null;
            if (!this.manuallyClosed && this.ws === null) { this.connect(); }
          }, this.reconnectDelay);
        }
      };

      socket.onerror = (error) => {
        if (this.ws !== socket || this.manuallyClosed) { return; }
        this.handlers.onError?.(error);
      };
    } catch (error) {
      console.error('Error creating WebSocket connection:', error);
      this.handlers.onError?.(error as Event);
    }
  }

  private shouldDispatchTurnEvent(message: WebSocketMessage): boolean {
    const turnMessage = message as WebSocketTurnMetadata;
    const sequence = turnMessage.sequence;
    if (!turnMessage.turnId || typeof sequence !== 'number' || !Number.isSafeInteger(sequence)) {
      return true;
    }
    const previous = this.lastTurnSequence.get(turnMessage.turnId);
    if (previous !== undefined && sequence <= previous) {
      console.warn('Dropped stale voice turn event', {
        turnId: turnMessage.turnId,
        sequence,
        previous,
      });
      return false;
    }
    this.lastTurnSequence.set(turnMessage.turnId, sequence);
    return true;
  }

  /**
   * 发送音频数据
   */
  sendAudio(audioData: string): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message: WebSocketAudioMessage = {
        type: 'audio',
        data: audioData,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  /**
   * 发送控制消息
   */
  sendControl(action: string, data?: Record<string, unknown>): boolean {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      const message = {
        type: 'control',
        action,
        data,
        timestamp: Date.now(),
      };
      this.ws.send(JSON.stringify(message));
      return true;
    }
    console.warn('WebSocket is not connected');
    return false;
  }

  /**
   * 关闭连接
   */
  disconnect(): void {
    this.manuallyClosed = true;
    this.clearReconnectTimer();
    const socket = this.ws;
    this.ws = null;
    socket?.close(1000, 'User disconnected');
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) { clearTimeout(this.reconnectTimer); this.reconnectTimer = null; }
  }

  /**
   * 获取连接状态
   */
  getReadyState(): number {
    return this.ws?.readyState ?? WebSocket.CLOSED;
  }

  /**
   * 是否已连接
   */
  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }
}

