"""Audit supplied-message parity and observed final usage; never infer semantic success."""
import json
from pathlib import Path
import re
import sys


def metrics(text):
    result = {}
    for line in text.splitlines():
        token = re.search(r'gen_ai_token_type="([^"]+)"', line)
        if token:
            result[token[1]] = result.get(token[1], 0) + float(line.rsplit(' ', 1)[1])
    return result


def main(run, output_name='role-history-audit-final.json'):
    inputs = json.loads((run / 'role-inputs.json').read_text(encoding='utf-8'))
    rows = []
    next_submission_metrics = {}
    aggregate = {}
    for path in sorted(run.glob('*/events.jsonl')):
        group = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
        rows.extend(group)
        starts = [row for row in group if row['kind'] == 'begin']
        for previous, following in zip(starts, starts[1:]):
            next_submission_metrics[previous['trace']['index']] = following['metrics']
        # Fresh sequential process, zero warmup Chat and no concurrent submitted work.
        # The last snapshot includes usage that appeared between result and the next begin.
        if starts:
            first = metrics(starts[0]['metrics'])
            last = metrics([row for row in group if 'metrics' in row][-1]['metrics'])
            for key in set(first) | set(last):
                aggregate[key] = aggregate.get(key, 0) + last.get(key, 0) - first.get(key, 0)
    begins = {row['trace']['index']: row for row in rows if row['kind'] == 'begin'}
    requests = {}
    for row in rows:
        if row['kind'] == 'request':
            requests.setdefault(row['trace']['index'], []).append(row)
    calls = []
    for row in rows:
        if row['kind'] not in ('result', 'failure'):
            continue
        index = row['trace']['index']
        before, after = metrics(begins[index]['metrics']), metrics(row['metrics'])
        delta = {key: after.get(key, 0) - before.get(key, 0) for key in set(before) | set(after)}
        known = delta.get('total', 0) > 0
        status = 'observed_increment' if known else 'unknown_no_final_usage'
        # Only one successful request in this controlled sequential call. Before the
        # following submission is an uncontaminated late observation of its final usage.
        if not known and row.get('success') and row['requests'] == 1 and index in next_submission_metrics:
            endpoint = metrics(next_submission_metrics[index])
            late = {key: endpoint.get(key, 0) - before.get(key, 0) for key in set(before) | set(endpoint)}
            if late.get('total', 0) > 0:
                delta, known, status = late, True, 'observed_before_next_submission'
        calls.append(dict(trace=row['trace'], success=row.get('success', False),
                          content=row.get('content'), elapsedMs=row['elapsedMs'],
                          completeQuestionMs=next((item['elapsedMs'] for item in row['callbacks']
                                                   if item['kind'] == 'complete_question'), None),
                          requestAttempts=row['requests'], finalUsage=delta if known else None,
                          usageStatus=status,
                          suppliedRequests=requests.get(index, [])))
    pairs = []
    for item in inputs['cases']:
        arms = {row['trace']['arm']: row for row in calls if row['trace']['caseId'] == item['id']}
        if len(arms) != 2:
            continue
        a, b = arms['flat']['suppliedRequests'][0], arms['roles']['suppliedRequests'][0]
        current_a = a['user'].split('【当前对话】\n', 1)[-1]
        role_texts = [message['text'] for message in b['historyMessages']]
        preserved = len(role_texts) == len(item['history']) and all(text in a['user'] for text in role_texts)
        same = a['systemSha256'] == b['systemSha256'] and a['options'] == b['options'] and current_a == b['user']
        roles = [message['role'] for message in b['historyMessages']]
        expected_roles = ['assistant' if text.startswith('面试官：') else 'user' for text in item['history']]
        same_no_history = (a['userSha256'] == b['userSha256'] and a['historyMessages'] == b['historyMessages']) if not item['history'] else None
        pairs.append(dict(id=item['id'], parityPassed=same and preserved and roles == expected_roles,
                          sameSystemOptionsCurrentInput=same, historyBodyPreserved=preserved,
                          rolesMatchTrustedLabels=roles == expected_roles,
                          noHistoryByteEquivalent=same_no_history))
    usage = {}
    for call in calls:
        if call['finalUsage'] is not None:
            for key, value in call['finalUsage'].items():
                usage[key] = usage.get(key, 0) + value
    summary = dict(scope=inputs['scope'], completed=len(calls), successful=sum(row['success'] for row in calls),
                   suppliedRequestAttempts=sum(row['requestAttempts'] for row in calls),
                   parityPairs=pairs, parityFailures=sum(not row['parityPassed'] for row in pairs),
                   finalObservedUsage=usage, independentlyObservedAggregate=aggregate,
                   aggregateEqualsPerCall=(usage == aggregate),
                   delayedFinalUsageRecovered=sum(row['usageStatus'] == 'observed_before_next_submission' for row in calls),
                   unknownFinalUsageCalls=sum(row['finalUsage'] is None for row in calls),
                   calls=calls, semanticVerdict='requires item-level self-review; not independently blinded')
    if Path(output_name).name != output_name:
        raise ValueError('Output name must not contain a path')
    output = run / output_name
    if output.exists():
        raise ValueError('Audit exists; use a new output name rather than rewrite frozen evidence')
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in summary.items() if key != 'calls'}, ensure_ascii=False))
    for call in sorted(calls, key=lambda row: row['trace']['index']):
        print(call['trace'], call['content'], call['finalUsage'])


if __name__ == '__main__':
    main(Path(sys.argv[1]))
