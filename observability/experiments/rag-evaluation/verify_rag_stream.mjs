/** Exercise the actual TS API, decoder and parser with fragmented controlled HTTP bodies; no model. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs/promises';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const require = createRequire(path.join(root, 'frontend/package.json'));
const { build } = createRequire(require.resolve('vite'))('esbuild');
const compiled = await build({entryPoints: [path.join(root, 'frontend/src/api/ragChat.ts')], bundle: true,
  platform: 'node', format: 'esm', write: false, packages: 'external',
  define: {'import.meta.env.VITE_API_BASE_URL': '"http://127.0.0.1:18080"'}});
const bundle = path.join(root, 'frontend/.rag-stream-protocol-verification.mjs');
await fs.writeFile(bundle, compiled.outputFiles[0].text);
const oldFetch = globalThis.fetch;
const results = [];
try {
  const { ragChatApi } = await import(pathToFileURL(bundle));
  const frame = (event, fields = {}, crlf = false) => {
    const nl = crlf ? '\r\n' : '\n';
    return `event: ${event}${nl}data: ${JSON.stringify({event, messageId: 7, ...fields})}${nl}${nl}`;
  };
  const start = frame('start');
  const terminal = frame('terminal', {generationState: 'COMPLETED'});
  const run = async (name, text, expected, sizes = [1], headers = {'content-type': 'text/event-stream'}) => {
    let cursor = 0, reads = 0, cancelled = false;
    const bytes = new TextEncoder().encode(text);
    const body = new ReadableStream({pull(controller) {
      if (cursor >= bytes.length) return controller.close();
      const size = sizes[reads++ % sizes.length];
      controller.enqueue(bytes.slice(cursor, cursor += size));
    }, cancel() { cancelled = true; }});
    const response = new Response(body, {headers});
    globalThis.fetch = async () => response;
    const events = [], errors = [], chunks = [];
    await ragChatApi.sendMessageStream(1, '受控公开问题', {signal: new AbortController().signal,
      onStart: id => events.push(['start', id]), onDelta: value => chunks.push(value),
      onTerminal: value => events.push(['terminal', value.generationState]), onError: error => errors.push(error.message)});
    const result = {name, events, content: chunks.join(''), errors, readerReleased: !body.locked, cancelled};
    expected(result);
    if (headers['content-type'] === 'text/event-stream') assert.equal(result.readerReleased, true);
    else assert.equal(response.bodyUsed, true);
    results.push(result);
  };
  const content = '中文😀\n实际换行\r\n字面\\n和\\r，`path\\new`';
  await run('unicode-crlf-one-byte', frame('start', {}, true) + frame('delta', {content}, true) + frame('terminal', {generationState:'COMPLETED'}, true),
    r => { assert.equal(r.content, content); assert.equal(r.errors.length, 0); assert.deepEqual(r.events, [['start', 7], ['terminal','COMPLETED']]); });
  await run('multiline-json-event', start + 'event: delta\ndata: {"event":"delta",\ndata: "messageId":7,"content":"两行 JSON"}\n\n' + terminal,
    r => { assert.equal(r.content,'两行 JSON'); assert.equal(r.errors.length, 0); }, [5, 1, 19]);
  await run('partial-failed-terminal', start + frame('delta', {content:'前缀'}) + frame('terminal', {generationState:'FAILED', errorCode:'GENERATION_FAILED'}),
    r => { assert.equal(r.content, '前缀'); assert.deepEqual(r.events.at(-1), ['terminal','FAILED']); assert.equal(r.errors.length, 0); });
  await run('cancelled-terminal', start + frame('terminal', {generationState:'CANCELLED'}),
    r => assert.deepEqual(r.events.at(-1), ['terminal','CANCELLED']));
  await run('eof-is-not-success', start + frame('delta', {content:'断连前缀'}),
    r => { assert.equal(r.content,'断连前缀'); assert.equal(r.events.length, 1); assert.match(r.errors[0], /未完成/); });
  await run('truncated-terminal-is-not-success', start + terminal.slice(0,-1),
    r => { assert.equal(r.events.length,1); assert.match(r.errors[0], /未完成/); });
  await run('duplicate-terminal', start + terminal + terminal,
    r => { assert.equal(r.events.filter(e=>e[0]==='terminal').length,1); assert.equal(r.errors.length,1); });
  await run('wrong-message-id', start + frame('delta', {messageId:8, content:'串写'}),
    r => { assert.equal(r.content,''); assert.match(r.errors[0], /当前请求/); });
  await run('delta-before-start', frame('delta', {content:'错序'}),
    r => { assert.equal(r.content,''); assert.equal(r.errors.length,1); });
  await run('data-after-terminal', start + terminal + frame('delta', {content:'迟到'}),
    r => { assert.equal(r.content,''); assert.equal(r.errors.length,1); });
  await run('malformed-json-cancels-reader', start + 'event: delta\ndata: {malformed}\n\n' + 'x'.repeat(100),
    r => { assert.equal(r.errors.length,1); assert.equal(r.cancelled,true); }, [1]);
  await run('business-error-response', JSON.stringify({code:8001,message:'受控限流',data:null}),
    r => { assert.equal(r.events.length,0); assert.equal(r.errors[0],'受控限流'); }, [200], {'content-type':'application/json'});
  await run('heartbeat-does-not-become-body', start + frame('heartbeat') + frame('delta',{content:'正文'}) + terminal,
    r => { assert.equal(r.content,'正文'); assert.equal(r.errors.length,0); });
  // Genuine abort rejection while a read is pending, plus reader cancellation/release.
  const abort = new AbortController(); let abortCancelled = false;
  const response = new Response(new ReadableStream({start(controller) {
    controller.enqueue(new TextEncoder().encode(start + frame('delta',{content:'取消前缀'})));
    abort.signal.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), {once:true});
  }, cancel() { abortCancelled = true; }}), {headers:{'content-type':'text/event-stream'}});
  globalThis.fetch = async () => response;
  let abortContent = '', abortError = '';
  await ragChatApi.sendMessageStream(1,'受控公开问题',{signal:abort.signal,onStart:()=>{},onDelta:chunk=> {
    abortContent += chunk; queueMicrotask(()=>abort.abort());
  },onTerminal:()=>assert.fail('abort cannot become success'),onError:error=>{abortError=error.message;}});
  assert.equal(abortContent,'取消前缀'); assert.ok(abortError); assert.equal(response.body.locked,false);
  results.push({name:'abort-releases-reader',content:abortContent,error:abortError,readerReleased:!response.body.locked,
    cancelInvoked:abortCancelled,note:'An already errored stream may reject cancel; lock must still release.'});
  const report = {passed:results.length, modelCalls:0, fixtureOnly:true, results};
  if (process.argv[2]) await fs.writeFile(process.argv[2], JSON.stringify(report,null,2));
  console.log(JSON.stringify({passed:results.length,modelCalls:0,readerLocksReleased:true}));
} finally {
  globalThis.fetch = oldFetch;
  await fs.unlink(bundle);
}
