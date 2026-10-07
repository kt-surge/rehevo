import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
registerHooks({ resolve(specifier, context, nextResolve) {
  try { return nextResolve(specifier, context); }
  catch (error) {
    if (error.code === 'ERR_MODULE_NOT_FOUND' && specifier.startsWith('.') && !specifier.endsWith('.ts')) {
      return nextResolve(specifier + '.ts', context);
    }
    throw error;
  }
} });
const { VoiceAudioScheduler } = await import('../../../frontend/src/utils/voiceAudioScheduler.ts');
import { VoicePcmFrameDecoder, decodeVoiceWav } from '../../../frontend/src/utils/voicePcmDecoder.ts';
import { VoiceTurnTelemetry } from '../../../frontend/src/utils/voiceTurnTelemetry.ts';

const schedulers = [];
afterEach(() => schedulers.splice(0).forEach(scheduler => scheduler.cancel()));
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

function fixture(extra = {}) {
  let clock = 100;
  const starts = [], observations = [], errors = [], trace = [];
  let drained = 0;
  const context = { currentTime: 0, state: 'running', destination: {}, nodes: [],
    async resume() { this.state = 'running'; },
    createBufferSource() {
      const node = { onended: null, stopCalls: 0, disconnectCalls: 0,
        connect() {}, start(at) { this.startAt = at; }, stop() { this.stopCalls++; },
        disconnect() { this.disconnectCalls++; } };
      this.nodes.push(node); return node;
    } };
  const scheduler = new VoiceAudioScheduler(context, { now: () => clock,
    onStarted: observation => { starts.push(observation.atMs); observations.push(observation); }, onError: error => errors.push(error),
    onDrained: () => drained++, onTrace: event => trace.push(event), ...extra });
  schedulers.push(scheduler); scheduler.beginTurn('turn-1');
  return { context, scheduler, starts, observations, errors, trace, drained: () => drained, setClock: at => { clock = at; } };
}

const buffer = duration => ({ length: Math.round(duration * 24000), duration });

test('提前排程所有片段，下一段起点接前段终点，不依赖onended', async () => {
  const f = fixture();
  await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.context.currentTime = 0.05;
  await f.scheduler.enqueue(buffer(0.6), 'turn-1');
  assert.equal(f.context.nodes[0].startAt, 0.012);
  assert.ok(Math.abs(f.context.nodes[1].startAt - 0.412) < 1e-12);
  assert.equal(f.scheduler.isBusy(), true);
  assert.equal(f.trace.filter(e => e.kind === 'scheduled')[1].gapMs, 0);
});

test('取消停止当前与全部未来Source，迟到onended不重新排播', async () => {
  const f = fixture();
  for (let index = 0; index < 3; index++) { await f.scheduler.enqueue(buffer(0.4), 'turn-1'); }
  const lateEnded = f.context.nodes[0].onended;
  f.scheduler.cancel(); lateEnded();
  assert.deepEqual(f.context.nodes.map(node => node.stopCalls), [1, 1, 1]);
  assert.equal(f.scheduler.isBusy(), false);
  assert.equal(f.drained(), 0);
  assert.deepEqual(f.starts, []);
});

test('等待浏览器resume时取消，迟到恢复不创建Source或报错', async () => {
  const f = fixture(); f.context.state = 'suspended';
  let release;
  f.context.resume = () => new Promise(resolve => { release = resolve; });
  const enqueued = f.scheduler.enqueue(buffer(0.4), 'turn-1');
  await wait(0); f.scheduler.cancel(); await enqueued;
  f.context.state = 'running'; release(); await wait(0);
  assert.deepEqual(f.context.nodes, []); assert.deepEqual(f.errors, []);
});

test('不支持输出时间戳时，跨过起点后报告观察上界且只报一次', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  await wait(4); assert.deepEqual(f.starts, []);
  f.context.currentTime = 0.05; f.setClock(150); await wait(20);
  assert.equal(f.starts.length, 1); assert.equal(f.starts[0], 150);
  assert.equal(f.observations[0].playbackMode, 'scheduled_pcm');
  await wait(10); assert.equal(f.starts.length, 1);
});

test('暂停时不报未来起播，恢复后的性能时钟包含暂停等待', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.context.state = 'suspended'; await wait(15); assert.deepEqual(f.starts, []);
  f.context.state = 'running'; f.context.currentTime = 0.03; f.setClock(200); await wait(70);
  assert.equal(f.starts.length, 1); assert.equal(f.starts[0], 200);
});

test('后台起播回调晚到，仍用输出时间戳记录原起点再结束', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1'); f.scheduler.finish();
  f.context.getOutputTimestamp = () => ({ contextTime: 0.6, performanceTime: 700 });
  f.context.currentTime = 0.6; f.setClock(700); f.context.nodes[0].onended();
  assert.equal(f.starts.length, 1); assert.ok(Math.abs(f.starts[0] - 112) < 0.0001);
  assert.equal(f.drained(), 1);
  assert.deepEqual(f.trace.filter(e => ['started','drained'].includes(e.kind)).map(e => e.kind), ['started','drained']);
});

test('渲染时钟已跨起点而输出位置未跨时，不能提前报告设备输出估计', async () => {
  const f = fixture();
  f.context.getOutputTimestamp = () => ({ contextTime: 0.002, performanceTime: 102 });
  await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.context.currentTime = 0.05; f.setClock(150); await wait(20);
  assert.deepEqual(f.starts, []);
  f.context.getOutputTimestamp = () => ({ contextTime: 0.05, performanceTime: 150 });
  await wait(20); assert.ok(Math.abs(f.starts[0] - 112) < 0.0001);
  assert.equal(f.observations[0].playbackMode, 'output_pcm');
});

test('不可信输出时间戳不会形成早于排程的样本，结束时降为观察上界', async () => {
  const f = fixture();
  f.context.getOutputTimestamp = () => ({ contextTime: 0.06, performanceTime: 110 });
  await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.context.currentTime = 0.06; f.setClock(160); await wait(20);
  assert.deepEqual(f.starts, []);
  f.context.nodes[0].onended(); assert.deepEqual(f.starts, [160]);
  assert.equal(f.observations[0].clockBasis, 'audio_timeline_observed');
});

test('诊断观察器失败不阻止排程或取消全部音频', async () => {
  const f = fixture({ onTrace() { throw new Error('受控观察器失败'); } });
  await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  assert.equal(f.context.nodes.length, 2);
  f.scheduler.cancel(); assert.deepEqual(f.context.nodes.map(node => node.stopCalls), [1,1]);
  assert.deepEqual(f.errors, []);
});

test('起播上报拒绝未来时刻，不能把预排起点当成已播放', () => {
  const telemetry = new VoiceTurnTelemetry(() => 100);
  telemetry.submit('r'); telemetry.bindTurn('t','r'); telemetry.receivedAudio('t');
  assert.equal(telemetry.playbackStarted('output_pcm', 101), null);
});

test('只有finish且所有Source结束才结束轮次，重复finish不重复结束', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  await f.scheduler.enqueue(buffer(0.4), 'turn-1'); f.scheduler.finish();
  f.context.currentTime = 0.5; f.setClock(600); f.context.nodes[0].onended();
  assert.equal(f.drained(), 0);
  f.context.currentTime = 0.9; f.setClock(1000); f.context.nodes[1].onended();
  f.scheduler.finish(); assert.equal(f.drained(), 1);
});

test('真正欠载时记录间隙，不用增加缓冲隐藏等待', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.context.currentTime = 0.6; await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  const e = f.trace.filter(e => e.kind === 'scheduled')[1];
  assert.ok(Math.abs(e.gapMs - 200) < 0.0001);
});

test('待播秒数和Source数量受限，溢出关闭已排Source并明确抛错', async () => {
  const f = fixture({ maxPendingSeconds: 1, maxSources: 2 });
  await f.scheduler.enqueue(buffer(0.6), 'turn-1');
  assert.throws(() => f.scheduler.enqueue(buffer(0.6), 'turn-1'), /超过上限/);
  assert.equal(f.context.nodes[0].stopCalls, 1); assert.equal(f.scheduler.isBusy(), false);
  const g = fixture({ maxSources: 1 }); await g.scheduler.enqueue(buffer(0.2), 'turn-1');
  assert.throws(() => g.scheduler.enqueue(buffer(0.2), 'turn-1'), /超过上限/);
});

test('旧轮次无法排音频，新轮次从新时间线开始', async () => {
  const f = fixture(); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  f.scheduler.beginTurn('turn-2'); await f.scheduler.enqueue(buffer(0.4), 'turn-1');
  assert.equal(f.context.nodes.length, 1);
  f.context.currentTime = 0.2; await f.scheduler.enqueue(buffer(0.4), 'turn-2');
  assert.ok(Math.abs(f.context.nodes[1].startAt - 0.212) < 0.0001);
});

function frame(overrides = {}) {
  return { type: 'audio_frame', turnId: 't', sentenceIndex: 0, frameIndex: 0, endOfSentence: false,
    encoding: 'pcm_s16le', sampleRate: 24000, channels: 1, bitsPerSample: 16,
    data: Buffer.from([0,128,255,127]).toString('base64'), ...overrides };
}

test('音频帧保持字节与小端样本，句末不被当成音频', () => {
  const decoder = new VoicePcmFrameDecoder(); decoder.beginTurn('t');
  assert.deepEqual([...decoder.accept(frame()).samples], [-1, 32767/32768]);
  assert.equal(decoder.accept(frame({ frameIndex: 1, endOfSentence: true, data: '' })), null);
  decoder.finish(); assert.throws(() => decoder.accept(frame()), /无效/);
});

test('缺帧、乱序、非PCM、错误采样率和空音频均明确拒绝', () => {
  for (const override of [{ frameIndex: 1 }, { channels: 2 }, { encoding: 'wav' },
    { sampleRate: 16000 }, { data: '' }, { data: 'AQ==' }, { endOfSentence: true, data: '' }]) {
    const decoder = new VoicePcmFrameDecoder(); decoder.beginTurn('t');
    assert.throws(() => decoder.accept(frame(override)));
  }
  const decoder = new VoicePcmFrameDecoder(); decoder.beginTurn('t'); decoder.accept(frame());
  assert.throws(() => decoder.finish(), /完整结束/); decoder.clear();
  assert.equal(decoder.accept(frame()), null);
});

function wav(rate = 24000, extra = false) {
  const header = Buffer.alloc(36); header.write('RIFF'); header.write('WAVE',8); header.write('fmt ',12);
  header.writeUInt32LE(16,16); header.writeUInt16LE(1,20); header.writeUInt16LE(1,22);
  header.writeUInt32LE(rate,24); header.writeUInt32LE(rate*2,28);
  header.writeUInt16LE(2,32); header.writeUInt16LE(16,34);
  const metadata = extra ? Buffer.from([74,85,78,75,1,0,0,0,0,0]) : Buffer.alloc(0);
  const audio = Buffer.from([100,97,116,97,4,0,0,0,0,128,255,127]);
  const value = Buffer.concat([header, metadata, audio]); value.writeUInt32LE(value.length-8,4);
  return value.toString('base64');
}

test('WAV检查RIFF长度/PCM格式且支持合法附加块，避免固定44字节假设', () => {
  assert.deepEqual([...decodeVoiceWav(wav()).samples], [-1,32767/32768]);
  assert.deepEqual([...decodeVoiceWav(wav(24000,true)).samples], [-1,32767/32768]);
  assert.throws(() => decodeVoiceWav(wav(16000)), /格式不支持/);
  assert.throws(() => decodeVoiceWav('AQID'), /WAV头/);
});

test('计时回调晚到仍可按已观察的排程起点报告，不把回调等待计为起播等待', () => {
  let clock = 100; const telemetry = new VoiceTurnTelemetry(() => clock);
  telemetry.submit('r'); telemetry.bindTurn('t','r'); clock = 105; telemetry.receivedAudio('t');
  clock = 150;
  assert.equal(telemetry.playbackStarted('scheduled_pcm',112).submitToPlaybackStartMs,12);
});
