import test, { afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import { VoiceInterviewWebSocket } from '../../../frontend/src/utils/voiceInterviewSocket.ts';

const originalSocket = globalThis.WebSocket;
const connections = [];
class FakeSocket {
  static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
  static sockets = [];
  readyState = 0;
  constructor(url) { this.url = url; FakeSocket.sockets.push(this); }
  close(code = 1000) { this.readyState = 3; this.onclose?.({ code, wasClean: true }); }
  open() { this.readyState = 1; this.onopen?.(); }
  serverClose(code, wasClean = true) { this.readyState = 3; this.onclose?.({ code, wasClean }); }
  message(value) { this.onmessage?.({ data: JSON.stringify(value) }); }
  send(value) { this.lastSent = JSON.parse(value); }
}

function fixture(handlers = {}) {
  globalThis.WebSocket = FakeSocket; FakeSocket.sockets = [];
  mock.timers.enable({ apis: ['setTimeout'] });
  const closed = [], received = [];
  const connection = new VoiceInterviewWebSocket(910, 'ws://127.0.0.1/controlled', {
    onClose: e => closed.push(e.code), onAudioFrame: frame => received.push(frame), ...handlers });
  connections.push(connection); connection.connect();
  return { connection, closed, received, socket: FakeSocket.sockets[0] };
}

afterEach(() => {
  connections.splice(0).forEach(connection => connection.disconnect());
  mock.timers.reset(); globalThis.WebSocket = originalSocket;
});

test('干净握手的1011仍在2秒后恢复，正常1000和协议策略1008不重连', () => {
  const f = fixture(); f.socket.open(); f.socket.serverClose(1011, true);
  mock.timers.tick(1999); assert.equal(FakeSocket.sockets.length, 1);
  mock.timers.tick(1); assert.equal(FakeSocket.sockets.length, 2);
  assert.deepEqual(f.closed, [1011]);
  FakeSocket.sockets[1].open(); FakeSocket.sockets[1].serverClose(1000, true);
  mock.timers.tick(4000); assert.equal(FakeSocket.sockets.length, 2);
  f.connection.connect(); FakeSocket.sockets[2].open(); FakeSocket.sockets[2].serverClose(1008, false);
  mock.timers.tick(4000); assert.equal(FakeSocket.sockets.length, 3);
});

test('用户退出取消已经排定的重连，迟到open和close不能复活连接', () => {
  const f = fixture(); f.socket.open(); f.socket.serverClose(1006, false);
  f.connection.disconnect(); f.socket.open(); f.socket.serverClose(1011, true);
  mock.timers.tick(5000); assert.equal(FakeSocket.sockets.length, 1);
  assert.equal(f.connection.isConnected(), false); assert.deepEqual(f.closed, [1006]);
});

test('替换连接后的旧事件不能覆盖新状态，新的sequence从头开始', () => {
  const f = fixture(); f.socket.open();
  const frame = { type:'audio_frame', turnId:'t', sequence:100, data:'AA==' };
  f.socket.message(frame);
  f.connection.connect(); const current = FakeSocket.sockets[1]; current.open();
  f.socket.message({ ...frame, sequence:101 }); f.socket.open(); f.socket.serverClose(1011);
  current.message({ ...frame, sequence:1 }); current.message({ ...frame, sequence:1 });
  mock.timers.tick(3000);
  assert.equal(f.connection.isConnected(), true); assert.equal(FakeSocket.sockets.length, 2);
  assert.deepEqual(f.received.map(frame => frame.sequence), [100,1]); assert.deepEqual(f.closed, []);
});

test('连续建连失败最多重试3次，不无限重建', () => {
  const f = fixture();
  for (let index = 0; index < 4; index++) {
    FakeSocket.sockets[index].serverClose(1006, false); mock.timers.tick(2000);
  }
  mock.timers.tick(10000); assert.equal(FakeSocket.sockets.length, 4);
  assert.equal(f.connection.isConnected(), false);
});

test('onClose处理器主动结束时不再安排恢复', () => {
  let connection;
  const f = fixture({ onClose: () => connection.disconnect() }); connection = f.connection;
  f.socket.open(); f.socket.serverClose(1011, true); mock.timers.tick(5000);
  assert.equal(FakeSocket.sockets.length, 1);
});
