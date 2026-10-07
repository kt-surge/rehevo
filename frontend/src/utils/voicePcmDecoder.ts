import type { DecodedVoicePcm, VoiceAudioFrame } from '../types/voiceAudio';

function base64Bytes(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}

function decodeSamples(bytes: Uint8Array): DecodedVoicePcm {
  if (bytes.byteLength === 0 || bytes.byteLength % 2 !== 0) {
    throw new Error('无有效的16位PCM音频');
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const samples = new Float32Array(bytes.byteLength / 2);
  for (let index = 0; index < samples.length; index++) {
    samples[index] = view.getInt16(index * 2, true) / 32768;
  }
  return { samples, sampleRate: 24000 };
}

export function decodeVoiceWav(value: string): DecodedVoicePcm {
  const bytes = base64Bytes(value);
  const view = new DataView(bytes.buffer);
  const tag = (offset: number) => String.fromCharCode(...bytes.subarray(offset, offset + 4));
  if (bytes.length < 44 || tag(0) !== 'RIFF' || tag(8) !== 'WAVE'
      || view.getUint32(4, true) + 8 !== bytes.length) {
    throw new Error('WAV头或长度无效');
  }
  let validFormat = false;
  let data: Uint8Array | null = null;
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const size = view.getUint32(offset + 4, true);
    const start = offset + 8;
    if (start + size > bytes.length) { throw new Error('WAV片段长度无效'); }
    if (tag(offset) === 'fmt ') {
      validFormat = size >= 16 && view.getUint16(start, true) === 1
        && view.getUint16(start + 2, true) === 1 && view.getUint32(start + 4, true) === 24000
        && view.getUint32(start + 8, true) === 48000 && view.getUint16(start + 12, true) === 2
        && view.getUint16(start + 14, true) === 16;
    } else if (tag(offset) === 'data') {
      if (data !== null) { throw new Error('WAV音频片段重复'); }
      data = bytes.subarray(start, start + size);
    }
    offset = start + size + (size % 2);
  }
  if (!validFormat || data === null) { throw new Error('音频格式不支持'); }
  return decodeSamples(data);
}

/** WebSocket已有Turn sequence过滤；这里额外检查音频自己的连续句/帧编号。 */
export class VoicePcmFrameDecoder {
  private turnId: string | null = null;
  private sentenceIndex = 0;
  private frameIndex = 0;
  private finished = false;

  beginTurn(turnId: string): void {
    this.turnId = turnId; this.sentenceIndex = 0; this.frameIndex = 0; this.finished = false;
  }

  accept(frame: VoiceAudioFrame): DecodedVoicePcm | null {
    if (frame.turnId !== this.turnId) { return null; }
    if (this.finished || frame.encoding !== 'pcm_s16le' || frame.sampleRate !== 24000
        || frame.channels !== 1 || frame.bitsPerSample !== 16 || typeof frame.data !== 'string'
        || !Number.isInteger(frame.sentenceIndex) || !Number.isInteger(frame.frameIndex)
        || frame.sentenceIndex !== this.sentenceIndex || frame.frameIndex !== this.frameIndex
        || typeof frame.endOfSentence !== 'boolean') {
      throw new Error('音频帧格式或顺序无效');
    }
    if (frame.endOfSentence) {
      if (frame.data !== '' || this.frameIndex === 0) { throw new Error('音频句末无效'); }
      this.sentenceIndex++; this.frameIndex = 0;
      return null;
    }
    const decoded = decodeSamples(base64Bytes(frame.data));
    this.frameIndex++;
    return decoded;
  }

  finish(): void {
    if (this.turnId === null || this.frameIndex !== 0 || this.sentenceIndex === 0) {
      throw new Error('音频流未完整结束');
    }
    this.finished = true;
  }

  clear(): void { this.turnId = null; this.finished = true; }
}
