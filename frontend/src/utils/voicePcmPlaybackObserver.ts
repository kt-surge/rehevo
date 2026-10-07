import type { VoicePlaybackMode } from '../types/voiceTelemetry';

export interface VoicePcmStartObservation {
  atMs: number;
  playbackMode: VoicePlaybackMode;
  clockBasis: 'output_timestamp' | 'audio_timeline_observed';
}

/** 输出时间戳为浏览器估计；不支持时只报告已跨过起点的观察时间。 */
export class VoicePcmPlaybackObserver {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private pending: {
    startAt: number;
    earliestAtMs: number;
    callback: (observation: VoicePcmStartObservation) => void;
  } | null = null;
  private reported = false;

  constructor(private readonly context: AudioContext, private readonly now = () => performance.now()) {}

  observe(startAt: number, earliestAtMs: number, callback: (observation: VoicePcmStartObservation) => void): void {
    if (this.pending || this.reported) { return; }
    this.pending = { startAt, earliestAtMs, callback };
    this.poll();
  }

  flush(): void { this.check(true); }

  cancel(): void {
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    this.pending = null; this.reported = false;
  }

  private poll(): void {
    this.timer = null;
    if (!this.pending || this.check(false)) { return; }
    this.timer = setTimeout(() => this.poll(), this.context.state === 'running' ? 10 : 50);
  }

  private check(force: boolean): boolean {
    const pending = this.pending;
    if (!pending || this.reported) { return true; }
    if (this.context.state === 'closed') { this.cancel(); return true; }
    if (this.context.state !== 'running' || this.context.currentTime < pending.startAt) { return false; }
    const observedAtMs = this.now();
    if (typeof this.context.getOutputTimestamp === 'function') {
      try {
        const timestamp = this.context.getOutputTimestamp();
        if (Number.isFinite(timestamp.contextTime) && Number.isFinite(timestamp.performanceTime)
            && timestamp.contextTime! >= pending.startAt && timestamp.performanceTime! > 0) {
          const atMs = timestamp.performanceTime! + (pending.startAt - timestamp.contextTime!) * 1000;
          if (atMs >= pending.earliestAtMs && atMs <= observedAtMs) {
            return this.report({ atMs, playbackMode: 'output_pcm', clockBasis: 'output_timestamp' });
          }
        }
        if (!force) { return false; }
      } catch (error) { console.warn('浏览器输出时间戳不可用，改用音频时间线观察', error); }
    }
    // 不把渲染时钟与页面时钟直接相减；回调晚到时这是保守上界。
    return this.report({ atMs: observedAtMs, playbackMode: 'scheduled_pcm', clockBasis: 'audio_timeline_observed' });
  }

  private report(observation: VoicePcmStartObservation): boolean {
    const callback = this.pending?.callback;
    this.reported = true; this.pending = null;
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    callback?.(observation);
    return true;
  }
}
