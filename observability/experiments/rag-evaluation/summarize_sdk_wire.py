"""Gate saved local connection evidence, resolved dependencies and application regression."""
import hashlib
import json
import re
from pathlib import Path

BASE = Path(__file__).resolve().parent / 'runs'


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def token_total(path, operation):
    return sum(float(value) for labels, value in re.findall(
        r'^gen_ai_client_token_usage_total\{([^\n]+)\} ([0-9.eE+-]+)$',
        path.read_text(encoding='utf-8-sig'), re.M)
        if f'gen_ai_operation_name="{operation}"' in labels
        and 'gen_ai_token_type="total"' in labels)


def main():
    first = BASE / 'sdk-wire-cancellation-20261005-r1'
    second = BASE / 'sdk-wire-cancellation-20261005-r2'
    arms = [first / 'baseline-wire', first / 'candidate-wire', second / 'release-wire']
    cases = ('mid-cancel', 'before-first', 'before-headers', 'deadline')
    rows = []
    for index, arm in enumerate(arms):
        data = load(arm / 'wire-results.json')
        by_name = {item['case']: item for item in data['cases']}
        assert len(by_name) == 6
        assert by_name['normal']['content'] == '中文😀\\n第二段'
        assert by_name['normal']['lastUsage'] == 3
        assert by_name['normal']['terminal'] == 'ON_COMPLETE'
        for name in cases:
            assert by_name[name]['peerCloseObservedBeforeFixtureCleanup'] == (index > 0)
            assert by_name[name]['terminal'] == ('ON_ERROR' if name == 'deadline' else 'CANCEL')
        rows.append(dict(arm=arm.name, cancellationPeerCloses=sum(
            by_name[name]['peerCloseObservedBeforeFixtureCleanup'] for name in cases),
            cases=4, normalExactBodyAndUsage=True, actualModelCalls=0))
    dependencies = load(second / 'release-wire/dependencies.json')
    ai_versions = {d['version'] for d in dependencies if d['group'] == 'org.springframework.ai'}
    assert ai_versions == {'2.0.1'}, ai_versions
    gates = load(second / 'release-backend-gates.json')
    assert gates['test']['tests'] == 400 and gates['integrationTest']['tests'] == 4
    assert all(v['failures'] == 0 and v['errors'] == 0 for v in gates.values())
    http = second / 'actual-http'
    for name, state in (('normal', 'COMPLETED'), ('cancel', 'CANCELLED')):
        outcome = load(http / f'{name}-result.json')
        assert outcome['persistedState'] == state and outcome['terminalCount'] == 1
        assert outcome['exactStreamPersistedContent']
        assert outcome['completed'] == (state == 'COMPLETED')
    scope = load(http / 'scope-after.json')
    assert scope['unchanged'] and scope['counts']['sessions'] == scope['counts']['messages'] == 0
    before = load(second / 'baseline-source/manifest.json')['sources']
    after = load(second / 'candidate-source/manifest.json')['sources']
    old = {r['file']: r['sha256'] for r in before}
    new = {r['file']: r['sha256'] for r in after}
    changes = [name for name in sorted(old) if old[name] != new[name]]
    assert changes == ['gradle/libs.versions.toml'], changes
    usage = dict(actualGenerationRequests=2, unknownChatUsageRequests=1,
        knownChatTokens=int(token_total(http / 'metrics-after.txt', 'chat')
            - token_total(http / 'metrics-before.txt', 'chat')),
        knownEmbeddingTokens=int(token_total(http / 'metrics-after.txt', 'embedding')
            - token_total(http / 'metrics-before.txt', 'embedding')),
        localFixtureActualModelCalls=0)
    result = dict(adoption='Retain 2.0.1 catalog after local wire and actual application gates.',
        wireArms=rows, gates=gates, resolvedSpringAiVersions=sorted(ai_versions),
        sourceChanges=changes, actualHttpCases=2, usage=usage, scope=scope,
        latencyClaim=False, supplierBillingClaim=False,
        timingNote='Raw error-case closeAfterCancelMs uses a post-terminal timestamp; negative values are not latency. Before-headers close waited for delayed headers in this fixture.')
    (second / 'reviewed-summary.json').write_text(json.dumps(result, ensure_ascii=False,
        indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
