import type { VoiceAudioTrace } from '../types/voiceAudio';
import { VoicePcmPlaybackObserver, type VoicePcmStartObservation } from './voicePcmPlaybackObserver';

interface SchedulerOptions {
  onStarted: (observation: VoicePcmStartObservation) => void;
  onDrained: () => void;
  onError: (error: unknown) => void;
  onTrace?: (trace: VoiceAudioTrace) => void;
  now?: () => number;
  startupSlackSeconds?: number;
  maxPendingSeconds?: number;
  maxSources?: number;
}

/** 预排时间线；onended仅释放资源，不负责启动下一片段。 */
export class VoiceAudioScheduler {
  private turnId: string | null = null;
  private generation = 0;
  private nextStartAt = 0;
  private previousEndAt: number | null = null;
  private sources = new Set<AudioBufferSourceNode>();
  private queuedBuffers = 0;
  private pendingSeconds = 0;
  private chain = Promise.resolve();
  private controller = new AbortController();
  private firstScheduledAt: number | null = null;
  private finished = false;
  private drained = false;
  private readonly now: () => number;
  private readonly playbackObserver: VoicePcmPlaybackObserver;

  constructor(private readonly context: AudioContext, private readonly options: SchedulerOptions) {
    this.now = options.now ?? (() => performance.now());
    this.playbackObserver = new VoicePcmPlaybackObserver(context, this.now);
  }

  beginTurn(turnId: string): void {
    this.cancel(); this.turnId = turnId;
  }

  enqueue(buffer: AudioBuffer, turnId: string): Promise<void> {
    if (turnId !== this.turnId) { return Promise.resolve(); }
    if (this.finished || buffer.length === 0 || !Number.isFinite(buffer.duration) || buffer.duration <= 0) {
      this.cancel(); throw new Error('音频流状态无效');
    }
    if (this.pendingSeconds + buffer.duration > (this.options.maxPendingSeconds ?? 30)
        || this.sources.size + this.queuedBuffers >= (this.options.maxSources ?? 128)) {
      this.cancel(); throw new Error('待播音频超过上限');
    }
    const generation = this.generation;
    const signal = this.controller.signal;
    this.pendingSeconds += buffer.duration;
    this.queuedBuffers++;
    this.chain = this.chain.then(async () => {
      if (generation !== this.generation) { return; }
      await this.resume(signal);
      if (generation !== this.generation || turnId !== this.turnId) { return; }
      const startAt = Math.max(this.context.currentTime + (this.options.startupSlackSeconds ?? 0.012), this.nextStartAt);
      const source = this.context.createBufferSource();
      source.buffer = buffer;
      source.connect(this.context.destination);
      this.sources.add(source);
      this.queuedBuffers--;
      source.onended = () => {
        source.disconnect();
        if (generation !== this.generation) { return; }
        this.sources.delete(source);
        this.pendingSeconds = Math.max(0, this.pendingSeconds - buffer.duration);
        this.playbackObserver.flush();
        this.trace('ended');
        this.checkDrained();
      };
      const scheduledAtMs = this.now();
      source.start(startAt);
      this.trace('scheduled', { scheduledAtSeconds: startAt, durationSeconds: buffer.duration,
        gapMs: this.previousEndAt === null ? 0 : Math.max(0, (startAt - this.previousEndAt) * 1000) });
      this.nextStartAt = startAt + buffer.duration;
      this.previousEndAt = this.nextStartAt;
      if (this.firstScheduledAt === null) {
        this.firstScheduledAt = startAt;
        this.playbackObserver.observe(startAt, scheduledAtMs, observation => {
          if (generation !== this.generation) { return; }
          this.trace('started', { scheduledAtSeconds: startAt, clockBasis: observation.clockBasis }, observation.atMs);
          this.options.onStarted(observation);
        });
      }
    }).catch(error => {
      if (generation === this.generation) { this.fail(error); }
    });
    return this.chain;
  }

  private resume(signal: AbortSignal): Promise<void> {
    if (this.context.state === 'running') { return Promise.resolve(); }
    return new Promise((resolve, reject) => {
      let settled = false;
      let timer: ReturnType<typeof setTimeout> | null = null;
      const finish = (error?: unknown) => {
        if (settled) { return; }
        settled = true;
        if (timer !== null) { clearTimeout(timer); }
        signal.removeEventListener('abort', aborted);
        if (error) { reject(error); } else { resolve(); }
      };
      const aborted = () => finish(new Error('音频播放已取消'));
      signal.addEventListener('abort', aborted, { once: true });
      if (signal.aborted) { aborted(); return; }
      timer = setTimeout(() => finish(new Error('浏览器音频恢复超时')), 5000);
      this.context.resume().then(() => {
        finish(this.context.state === 'running' ? undefined : new Error('浏览器音频未恢复'));
      }, error => finish(error));
    });
  }

  finish(): void { this.finished = true; this.checkDrained(); }
  isBusy(): boolean { return this.queuedBuffers > 0 || this.sources.size > 0; }

  private checkDrained(): void {
    if (!this.finished || this.drained || this.isBusy()) { return; }
    this.drained = true;
    this.trace('drained');
    this.options.onDrained();
  }

  cancel(): void {
    const busy = this.isBusy();
    const startedAtMs = this.now();
    const stoppedSources = this.sources.size;
    this.generation++;
    this.controller.abort(); this.controller = new AbortController();
    this.playbackObserver.cancel();
    for (const source of this.sources) {
      source.onended = null;
      try { source.stop(); }
      catch (error) { console.warn('停止音频Source失败', error); }
      source.disconnect();
    }
    this.sources.clear(); this.queuedBuffers = 0; this.pendingSeconds = 0;
    if (busy) { this.trace('cancelled', { stoppedSources, localSourceCleanupMs: this.now() - startedAtMs }); }
    this.turnId = null; this.nextStartAt = 0; this.previousEndAt = null;
    this.firstScheduledAt = null; this.finished = false; this.drained = false;
    this.chain = Promise.resolve();
  }

  private fail(error: unknown): void { this.cancel(); this.options.onError(error); }

  private trace(kind: VoiceAudioTrace['kind'], extra: Partial<VoiceAudioTrace> = {}, atMs = this.now()): void {
    if (this.turnId === null) { return; }
    try {
      this.options.onTrace?.({ kind, turnId: this.turnId, generation: this.generation, atMs,
        pendingSources: this.sources.size, pendingSeconds: this.pendingSeconds, ...extra });
    } catch (error) { console.warn('音频诊断回调失败', error); }
  }
}
