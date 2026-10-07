import test from 'node:test';
import assert from 'node:assert/strict';
import { VoiceTurnTelemetry } from '../../../frontend/src/utils/voiceTurnTelemetry.ts';

function fixture() {
  let clock = 100;
  const telemetry = new VoiceTurnTelemetry(() => clock);
  return { telemetry, set: value => { clock = value; } };
}

test('开场与尚未收到音频不能构造起播样本', () => {
  const { telemetry } = fixture();
  telemetry.bindTurn('opening');
  telemetry.receivedAudio('opening');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
});

test('匹配请求与轮次后分开记录收帧及播放，保留小数毫秒', () => {
  const { telemetry, set } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  set(220.5);
  telemetry.receivedAudio('turn-1');
  set(280.75);
  assert.deepEqual(telemetry.playbackStarted('scheduled_pcm'), {
    clientRequestId: 'request-001', turnId: 'turn-1', playbackMode: 'scheduled_pcm',
    submitToAudioReceivedMs: 120.5, submitToPlaybackStartMs: 180.75,
  });
});

test('缺失或错误的请求号不能把开场和旧轮次算到本次提交', () => {
  const { telemetry } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('opening');
  telemetry.bindTurn('old-turn', 'request-other');
  telemetry.receivedAudio('old-turn');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
});

test('重复音频帧不会覆盖首帧时间，重复起播也不重复上报', () => {
  const { telemetry, set } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  set(200);
  telemetry.receivedAudio('turn-1');
  set(300);
  telemetry.receivedAudio('turn-1');
  assert.equal(telemetry.playbackStarted('html_playing').submitToAudioReceivedMs, 100);
  assert.equal(telemetry.playbackStarted('html_playing'), null);
});

test('取消和失败清理后迟到回调不构造指标', () => {
  const { telemetry } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  telemetry.receivedAudio('turn-1');
  telemetry.clear();
  telemetry.receivedAudio('turn-1');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
});

test('新提交不会继承前一次收帧和起播状态', () => {
  const { telemetry, set } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  telemetry.receivedAudio('turn-1');
  set(500);
  telemetry.submit('request-002');
  telemetry.bindTurn('turn-1', 'request-001');
  telemetry.receivedAudio('turn-1');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
  telemetry.bindTurn('turn-2', 'request-002');
  set(600);
  telemetry.receivedAudio('turn-2');
  set(650);
  assert.equal(telemetry.playbackStarted('scheduled_pcm').submitToPlaybackStartMs, 150);
});

test('未知轮次的音频不能提前填入首帧时间', () => {
  const { telemetry, set } = fixture();
  telemetry.submit('request-001');
  telemetry.bindTurn('turn-1', 'request-001');
  set(200);
  telemetry.receivedAudio('unknown');
  assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
});

test('时钟倒退、非有限和超预算时丢弃样本', () => {
  for (const now of [90, NaN, Infinity, 120_101]) {
    const { telemetry, set } = fixture();
    telemetry.submit('request-001');
    telemetry.bindTurn('turn-1', 'request-001');
    set(100);
    telemetry.receivedAudio('turn-1');
    set(now);
    assert.equal(telemetry.playbackStarted('scheduled_pcm'), null);
  }
});
