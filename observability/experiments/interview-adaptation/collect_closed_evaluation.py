"""Audit fixed-order public diagnostics without claiming a population effect."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def save(run, name, value):
    path = run / name
    if path.exists():
        assert json.loads(read(path)) == json.loads(json.dumps(value)), name
        return
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


runs = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
scope = data_scope()
summary = {}
for label, name in [('NONE', 'evaluation-closed-preflight-20261005-r1'),
                    ('DEFAULT', 'evaluation-closed-default-20261005-r1')]:
    run = runs / name
    inputs = json.loads(read(run / 'inputs.json'))
    report = json.loads(read(run / 'report.json'))
    result = json.loads(read(run / 'result.json'))
    parsed_path = run / '01-parsed-result.json'
    parsed = json.loads(read(parsed_path)) if parsed_path.exists() else None
    by_index = {q['questionIndex']: q for q in inputs['qaRecords']}
    eligible = {q['questionIndex'] for q in report['questionDetails']
                if q['evaluationStatus'] == 'SCORED' and q.get('answerEvidence')}
    invalid_tasks = [i for i, task in enumerate(report['trainingTasks'])
                     if not task['questionIndexes'] or not set(task['questionIndexes']).issubset(eligible)]
    details = {q['questionIndex']: q for q in report['questionDetails']}
    matches = {q['questionIndex']: [s for s in q.get('answerEvidence', [])
               if s and s in (by_index[q['questionIndex']].get('userAnswer') or '')]
               for q in report['questionDetails']}
    audit = dict(toolAccess=label, expectedRawIndexes=list(by_index),
         modelRawIndexes=[q['questionIndex'] for q in parsed['questionEvaluations']] if parsed else None,
         reportEvidenceMatches=matches, invalidTaskOrdinals=invalid_tasks,
         unansweredStatus=details[1]['evaluationStatus'], scored=report['scoredQuestions'],
         failed=report['failedQuestions'], rawOutputUnavailableForInvalidJson=not bool(parsed),
         mechanicalPreflightPassed=report['scoredQuestions'] == 1 and matches[0]
             and details[1]['evaluationStatus'] == 'UNANSWERED' and not invalid_tasks,
         semanticReview='NONE reflects the explicit transaction misconception, but includes an inferred '
             'self-call belief and arbitrary illustrative project reference; broader quality remains HOLD',
         qualityGate='HOLD', notFormalQualityOrLatency=True)
    usage = dict(input=0, output=0, total=0, observedChatOperations=0)
    for meter in json.loads(read(run / 'meters-after.json')):
        tags = {x['key']: x['value'] for x in meter['tags']}
        values = {x['statistic']: x['value'] for x in meter['measurements']}
        if tags.get('gen_ai.operation.name') != 'chat':
            continue
        if meter['name'] == 'gen_ai.client.token.usage':
            usage[tags['gen_ai.token.type']] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation':
            usage['observedChatOperations'] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation.active':
            assert values['ACTIVE_TASKS'] == 0
    assert usage['input'] + usage['output'] == usage['total']
    usage.update(crossModelComparisons=0, embedding=0, tts=0, asr=0,
                 supplierWireRequests='not captured; not equivalent to observed SDK operations')
    save(run, 'semantic-audit.json', audit)
    save(run, 'usage-summary.json', usage)
    save(run, 'scope-after.json', scope)
    assert json.loads(read(run / 'scope-before.json')) == scope
    summary[label] = dict(audit=audit, usage=usage, result=result)
left = runs / 'evaluation-closed-preflight-20261005-r1'
right = runs / 'evaluation-closed-default-20261005-r1'
assert read(left / 'inputs.json') == read(right / 'inputs.json')
assert json.loads(read(left / 'reference-context.json')) == json.loads(read(right / 'reference-context.json'))
assert read(left / 'source-manifest.json') == read(right / 'source-manifest.json')
integrity = dict(inputByteIdentical=True, referenceObjectIdentical=True, sourceManifestByteIdentical=True,
    fixedOrder=['NONE', 'DEFAULT'], pairs=1, alreadyExposed=True, notFormalEffectEstimate=True)
for run in (left, right):
    save(run, 'comparison-integrity.json', integrity)
print(json.dumps(summary, ensure_ascii=True))
