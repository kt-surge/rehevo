// Offline Markdown extraction. Offsets use Unicode code points, not UTF-16.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(path.join(process.cwd(), 'frontend/package.json'));
const markdownRequire = createRequire(require.resolve('react-markdown'));
const { unified } = await import(pathToFileURL(markdownRequire.resolve('unified')));
const { default: parse } = await import(pathToFileURL(markdownRequire.resolve('remark-parse')));
const parser = unified().use(parse);

export function extractCitationUnits(answer, suppliedIds) {
  const tree = parser.parse(answer);
  const definitions = new Set();
  const known = new Set(suppliedIds);
  const units = [];
  const skippedMarkers = [];
  const cp = offset => Array.from(answer.slice(0, offset)).length;
  const raw = node => answer.slice(node.position.start.offset, node.position.end.offset);
  const collectDefinitions = node => {
    if (node.type === 'definition') definitions.add(node.identifier.toLowerCase());
    node.children?.forEach(collectDefinitions);
  };
  collectDefinitions(tree);
  const marker = /\[(E[0-9]+)\]/g;
  const recordMarkers = (node, unit, reason) => {
    const source = raw(node);
    for (const match of source.matchAll(marker)) {
      const absolute = node.position.start.offset + match.index;
      let slashes = 0;
      for (let at = absolute - 1; at >= 0 && answer[at] === '\\'; at--) slashes++;
      const skipped = reason || (slashes % 2 ? 'escaped' : null);
      const entry = { evidenceId: match[1], start: cp(absolute), end: cp(absolute + match[0].length),
        text: match[0] };
      if (skipped) skippedMarkers.push({ ...entry, reason: skipped });
      else unit.citations.push({ ...entry, known: known.has(match[1]) });
    }
  };
  const visit = (node, unit) => {
    const excluded = ['code', 'inlineCode', 'link', 'definition', 'html'];
    if (excluded.includes(node.type)) {
      recordMarkers(node, unit, node.type);
      return;
    }
    if (node.type === 'linkReference') {
      const adjacent = /^(?:\[E[0-9]+\]\s*){2,}$/.test(raw(node));
      if (adjacent && !definitions.has(node.identifier.toLowerCase())) recordMarkers(node, unit, null);
      else recordMarkers(node, unit, 'linkReference');
      return;
    }
    if (node.type === 'text') recordMarkers(node, unit, null);
    else node.children?.forEach(child => visit(child, unit));
  };
  const blocks = node => {
    if (['paragraph', 'heading', 'code'].includes(node.type)) {
      const unit = { unitId: `U${units.length + 1}`, markdownType: node.type,
        start: cp(node.position.start.offset), end: cp(node.position.end.offset), text: raw(node), citations: [] };
      visit(node, unit);
      unit.citations = unit.citations.map((citation, index) => ({ ...citation,
        edgeId: `${unit.unitId}-C${index + 1}` }));
      units.push(unit);
      return;
    }
    if (['definition', 'html'].includes(node.type)) {
      recordMarkers(node, null, node.type);
      return;
    }
    node.children?.forEach(blocks);
  };
  blocks(tree);
  return { offsetUnit: 'unicode-code-points', scopePolicy: 'literal citation within its Markdown paragraph or heading; no inferred cross-block citation',
    units, skippedMarkers, actualCitationOccurrences: units.flatMap(unit => unit.citations).length };
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const rows = input.map(row => ({ ...row, extraction: extractCitationUnits(row.answer, row.evidenceIds) }));
  fs.writeFileSync(process.argv[3], JSON.stringify(rows, null, 2), { flag: 'wx' });
  console.log(JSON.stringify({ extracted: rows.length, modelCalls: 0 }));
}
