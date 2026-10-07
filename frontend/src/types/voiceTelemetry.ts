export type VoicePlaybackMode = 'scheduled_pcm' | 'output_pcm' | 'html_playing';

export interface VoiceClientPlaybackReport {
  clientRequestId: string;
  turnId: string;
  playbackMode: VoicePlaybackMode;
  submitToAudioReceivedMs: number;
  submitToPlaybackStartMs: number;
}
