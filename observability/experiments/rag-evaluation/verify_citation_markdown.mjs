// Exercise the actual Markdown parser and plugin; no browser or model calls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import vm from 'node:vm';

const root = process.cwd();
const require = createRequire(path.join(root, 'frontend/package.json'));
const ts = require('typescript');
const pluginSource = fs.readFileSync(path.join(root, 'frontend/src/utils/ragCitationMarkdown.ts'), 'utf8');
const compiled = ts.transpileModule(pluginSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const exports = {};
vm.runInNewContext(compiled, { exports });
const markdownRequire = createRequire(require.resolve('react-markdown'));
const { unified } = await import(pathToFileURL(markdownRequire.resolve('unified')));
const { default: parse } = await import(pathToFileURL(markdownRequire.resolve('remark-parse')));
const fixtures = [
  ['有效[E1]未知[E99]', ['#rag-evidence-7-E1']],
  ['两个[E1][E2]', ['#rag-evidence-7-E1', '#rag-evidence-7-E2']],
  ['`[E1]`\n```js\n[E2]\n```', []],
  ['\\[E1]', []],
  ['[E1](https://example.org)', ['https://example.org']],
  ['[E1][ref]\n\n[ref]: https://example.org', []],
  ['[E1][E2]\n\n[E2]: https://example.org', []],
];
for (const [text, expected] of fixtures) {
  const processor = unified().use(parse).use(exports.remarkRagCitations, { evidenceIds: ['E1', 'E2'], scope: '7' });
  const tree = processor.runSync(processor.parse(text), { value: text });
  const urls = [];
  const visit = node => { if (node.type === 'link') urls.push(node.url); node.children?.forEach(visit); };
  visit(tree);
  assert.deepEqual(urls, expected, text);
}
assert.notEqual(exports.evidenceAnchor('7', 'E1'), exports.evidenceAnchor('8', 'E1'));
console.log(JSON.stringify({ fixtures: fixtures.length, passed: true, crossMessageScopeDistinct: true,
  scope: 'actual remark-parse AST and source plugin, not browser UI verification' }));
