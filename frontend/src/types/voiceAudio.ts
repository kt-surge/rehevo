import type { WebSocketTurnMetadata } from '../api/voiceInterview';

export interface VoiceAudioFrame extends WebSocketTurnMetadata {
  type: 'audio_frame';
  turnId: string;
  data: string;
  sentenceIndex: number;
  frameIndex: number;
  endOfSentence: boolean;
  encoding: 'pcm_s16le';
  sampleRate: 24000;
  channels: 1;
  bitsPerSample: 16;
}

export interface DecodedVoicePcm {
  samples: Float32Array;
  sampleRate: 24000;
}

export interface VoiceAudioTrace {
  kind: 'scheduled' | 'started' | 'ended' | 'cancelled' | 'drained';
  turnId: string;
  generation: number;
  atMs: number;
  scheduledAtSeconds?: number;
  durationSeconds?: number;
  gapMs?: number;
  pendingSources?: number;
  pendingSeconds?: number;
  clockBasis?: 'output_timestamp' | 'audio_timeline_observed';
  stoppedSources?: number;
  discardedBuffers?: number;
  localSourceCleanupMs?: number;
}
