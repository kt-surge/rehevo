import assert from 'node:assert/strict';
import { extractCitationUnits } from './extract_citation_units.mjs';

const fixtures = [
  ['完全没有引用。', []],
  ['事实。[E1]', ['E1']],
  ['事实[E1][E2]。', ['E1', 'E2']],
  ['事实[E99][E01][E0]。', ['E99', 'E01', 'E0']],
  ['`[E1]`\n\n```js\n[E2]\n```', []],
  ['\\[E1]，但有效[E2]', ['E2']],
  ['\\\\[E1]', ['E1']],
  ['[E1](https://example.org) [E2]', ['E2']],
  ['[E1][ref]\n\n[ref]: https://example.org', []],
  ['[E1][E2]\n\n[E2]: https://example.org', []],
  ['**事实[E1]**，另一个事实[E2]', ['E1', 'E2']],
  ['😀事实。[E1]', ['E1']],
  ['> 事实[E1]\n\n- 另一个事实[E2]', ['E1', 'E2']],
  ['<span>[E1]</span> [E2]', ['E1', 'E2']],
  ['<div>\n[E1]\n</div>', []],
  ['同一来源两次[E1][E1]', ['E1', 'E1']],
  ['事实[E123456789012345678901234567890]', ['E123456789012345678901234567890']],
];
for (const [answer, expected] of fixtures) {
  const result = extractCitationUnits(answer, ['E1', 'E2']);
  assert.deepEqual(result.units.flatMap(unit => unit.citations.map(cite => cite.evidenceId)), expected, answer);
  const chars = Array.from(answer);
  for (const unit of result.units) {
    assert.equal(chars.slice(unit.start, unit.end).join(''), unit.text);
    for (const citation of unit.citations) {
      assert.equal(chars.slice(citation.start, citation.end).join(''), citation.text);
      assert.equal(citation.known, ['E1', 'E2'].includes(citation.evidenceId));
    }
  }
}
const separated = extractCitationUnits('第一段。\n\n第二段。[E1]', ['E1']);
assert.equal(separated.units[0].citations.length, 0);
assert.equal(separated.units[1].citations.length, 1);
console.log(JSON.stringify({ fixtures: fixtures.length + 1, passed: true, modelCalls: 0,
  scope: 'actual remark-parse AST, literal original offsets and reference-definition handling' }));
