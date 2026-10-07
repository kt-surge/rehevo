"""Summarize saved evidence only; do not manufacture usage on cancelled streams."""
import hashlib
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/rag-evaluation/runs'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def xml_counts(directory):
    total = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in directory.rglob('TEST-*.xml'):
        with open('\\\\?\\' + str(path.resolve()), 'rb') as handle:
            tree = ET.fromstring(handle.read())
        for name in total:
            total[name] += int(tree.attrib.get(name, 0))
    return total


def tokens(path, operation):
    text = path.read_text(encoding='utf-8-sig')
    return sum(float(value) for labels, value in re.findall(
        r'^gen_ai_client_token_usage_total\{([^\n]+)\} ([0-9.eE+-]+)$', text, re.M)
        if f'gen_ai_operation_name="{operation}"' in labels
        and 'gen_ai_token_type="total"' in labels)


def main():
    runs = []
    for number in (1, 2, 3):
        run = BASE / f'rag-stream-terminal-20261005-r{number}'
        differences = []
        for name in ('baseline-source', 'candidate-source'):
            manifest = read_json(run / name / 'manifest.json')
            for item in manifest['sources']:
                path = run / name / item['file']
                raw = open('\\\\?\\' + str(path.resolve()), 'rb').read()
                if hashlib.sha256(raw).hexdigest() != item['sha256']:
                    differences.append(f'{name}/{item["file"]}')
        assert not differences, differences
        http = run / 'actual-http'
        calls = []
        for case in ('normal', 'cancel'):
            path = http / f'{case}-result.json'
            if not path.exists():
                continue
            item = read_json(path)
            calls.append({key: item[key] for key in ('case', 'terminalCount',
                'persistedState', 'completed', 'exactStreamPersistedContent',
                'prefixCharacters')})
            assert item['terminalCount'] == 1
            assert item['exactStreamPersistedContent']
            assert item['completed'] == (item['persistedState'] == 'COMPLETED')
        scope = read_json(http / 'scope-after.json')
        assert scope['unchanged'] and scope['counts']['sessions'] == 0
        assert scope['counts']['messages'] == 0
        usage = {operation: int(tokens(http / 'metrics-after.txt', operation)
                 - tokens(http / 'metrics-before.txt', operation))
                 for operation in ('chat', 'embedding')}
        entry = dict(run=run.name, httpCases=calls, knownObservedTokens=usage,
                     unknownChatUsageRequests=1, scope=scope,
                     frozenSourceMismatches=differences,
                     backend=xml_counts(run / 'backend-proof'),
                     persistence=xml_counts(run / 'persistence-proof'))
        if number == 1:
            entry['baselineRegression'] = xml_counts(run / 'baseline-proof')
            entry['frontendProtocol'] = read_json(run / 'frontend-protocol-r3.json')
        if number == 3:
            browser = run / 'actual-browser'
            entry['actualBrowser'] = read_json(browser / 'result.json')
            browser_usage = {operation: int(tokens(browser / 'metrics-after.txt', operation)
                - tokens(browser / 'metrics-before-ready.txt', operation))
                for operation in ('chat', 'embedding')}
            entry['actualBrowserKnownTokens'] = browser_usage
        (run / 'summary-reviewed.json').write_text(json.dumps(entry, ensure_ascii=False,
            indent=2), encoding='utf-8')
        runs.append(entry)
    usage = dict(actualGenerationRequests=6, unknownChatUsageRequests=3,
        knownChatTokens=sum(r['knownObservedTokens']['chat'] for r in runs)
            + runs[2]['actualBrowserKnownTokens']['chat'],
        knownEmbeddingTokens=sum(r['knownObservedTokens']['embedding'] for r in runs)
            + runs[2]['actualBrowserKnownTokens']['embedding'],
        controlledFixtureModelCalls=0,
        note='Unknown means unavailable, not zero. The prior 8-call application model approval is not reused.')
    result = dict(runs=runs, usage=usage, wireCancellation='NOT_VERIFIED',
        adoption='Retain terminal correctness; no formal latency, quality or cost claim.')
    (BASE / 'rag-stream-terminal-20261005-r3' / 'aggregate-reviewed.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(usage=usage, backend=runs[2]['backend'],
        persistence=runs[0]['persistence'], baseline=runs[0]['baselineRegression']),
        ensure_ascii=False))


if __name__ == '__main__':
    main()
