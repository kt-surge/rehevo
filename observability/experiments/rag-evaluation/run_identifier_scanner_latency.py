"""New-run-only paired API cost follow-up after frozen scanner semantic parity.

Uses the existing 27-question test set for unchanged selection semantics. Repeated
calls add latency observations, not new quality samples. Stops on first failure.
"""
import json
import math
from pathlib import Path
import random
import shutil
import statistics
import subprocess
import time

import requests

from fact_gold import digest, load_gold
from ingest_primary_dev import BASE_URL, provider_snapshot, utc_now, write_json

ROOT = Path(__file__).resolve().parents[3]
OLD = ROOT / 'observability/experiments/rag-evaluation/runs/identifier-selection-test-20261002-r1'
RUN = ROOT / 'observability/experiments/rag-evaluation/runs/identifier-scan-20261004-r1'
SELECTOR = ROOT / 'app/src/main/java/interview/guide/modules/knowledgebase/service/IdentifierEvidenceSelector.java'


def p95(values):
    return sorted(values)[math.ceil(len(values) * .95) - 1]


def signature(item):
    return {'selected': [x['vectorDocumentId'] for x in item['evidence']],
            'candidates': [x['vectorDocumentId'] for x in item['candidateEvidence']],
            'budget': item['contextTokenBudget'], 'tokens': item['contextTokenEstimate'],
            'reservations': item['identifierReservations']}


def index_state(ids):
    sql = "SELECT json_agg(row_to_json(v) ORDER BY id) FROM (SELECT id::text,content,metadata,embedding::text FROM vector_store WHERE metadata->>'kb_id' IN (" + ','.join("'" + str(int(x)) + "'" for x in ids) + ")) v;"
    raw = subprocess.check_output(['docker', 'exec', 'rehevo-opt-20261001-postgres-1', 'psql',
        '-U', 'postgres', '-d', 'rehevo_opt', '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql],
        encoding='utf-8')
    rows = json.loads(raw)
    expected = json.loads((OLD / 'actual-chunks.json').read_text(encoding='utf-8'))
    actual = {x['id']: (x['content'], x['metadata']) for x in rows}
    frozen = {x['chunk_id']: (x['text'], x['metadata']) for x in expected}
    if actual != frozen:
        raise ValueError('Test document text/metadata/IDs changed; stop before provider calls')
    import hashlib
    return {'rows': len(rows), 'textMetadataIdsMatchOld': True,
            'vectorSha256': hashlib.sha256(json.dumps(rows, sort_keys=True, ensure_ascii=False).encode()).hexdigest(),
            'embeddingsExported': False}


def main():
    parity = json.loads((RUN / 'parity-cpu-r1/parity.json').read_text(encoding='utf-8'))
    if not parity['parityPassed'] or parity['candidateSourceSha256'] != digest(SELECTOR):
        raise ValueError('Scanner source no longer matches passing frozen parity')
    regression = json.loads((RUN / 'regression-r1/summary.json').read_text(encoding='utf-8'))
    if any(regression[x] for x in ['failures', 'errors', 'skipped']) or regression['tests'] < 322:
        raise ValueError('Fresh full regression missing')
    gold = load_gold(ROOT / 'data/local/fact-gold-identifier-test-20261002/manifest.json', 'agent_verified')
    ids = [x['knowledgeBaseId'] for x in json.loads((OLD / 'ingestion.manifest.json').read_text(encoding='utf-8'))['documents']]
    expected = {}
    for label, directory in [('A', 'a-hybrid6000-r1'), ('B', 'b-identifier6000-r1')]:
        for path in (OLD / directory).glob('batch-*-response.json'):
            for item in json.loads(path.read_text(encoding='utf-8'))['result']['data']['items']:
                expected[label, item['question']] = signature(item)
    if len(expected) != 2 * len(gold.cases):
        raise ValueError('Incomplete old reference')
    out = RUN / 'paired-api-r1'
    out.mkdir(exist_ok=False)
    source_dir = out / 'frozen-sources'
    source_dir.mkdir()
    paths = sorted((ROOT / 'app/src/main/java/interview/guide/modules/knowledgebase').rglob('*.java'))
    paths += [SELECTOR, ROOT / 'app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java',
              ROOT / 'app/src/main/java/interview/guide/common/config/LlmProviderProperties.java',
              ROOT / 'app/src/main/resources/application.yml', Path(__file__).resolve()]
    manifest = {}
    for i, path in enumerate(sorted(set(paths))):
        name = f'{i:03d}.source'
        shutil.copyfile(path, source_dir / name)
        manifest[str(path.relative_to(ROOT))] = {'file': name, 'sha256': digest(path)}
    write_json(source_dir / 'manifest.json', manifest)
    before_index = index_state(ids)
    write_json(out / 'index-before.json', before_index)
    before_provider = provider_snapshot()
    write_json(out / 'provider-before.json', before_provider)
    rng = random.Random(61004)
    plan = []
    for round_index in range(4):
        cases = list(gold.cases)
        rng.shuffle(cases)
        for case_id in cases:
            labels = ['A', 'B']
            rng.shuffle(labels)
            plan.extend({'round': round_index, 'caseId': case_id, 'label': label} for label in labels)
    write_json(out / 'request-plan.json', {'seed': 61004, 'plan': plan,
        'uniqueQuestions': len(gold.cases), 'modes': {'A': 'HYBRID', 'B': 'HYBRID_IDENTIFIER'},
        'minimumStartGapSeconds': .65, 'rewrite': False, 'budget': 6000, 'plannedRequests': len(plan),
        'qualityPolicyChanged': False, 'newQualitySamples': 0,
        'scannerParitySha256': digest(RUN / 'parity-cpu-r1/parity.json'),
        'goldManifestSha256': digest(gold.manifest_path), 'oldResultsNeverModified': True,
        'scope': 'four randomized paired passes in one isolated local session, not production latency'})
    records = []
    started = utc_now()
    previous_start = None
    with (out / 'records.jsonl').open('x', encoding='utf-8') as stream:
        for index, step in enumerate(plan):
            question = gold.cases[step['caseId']]['question']
            payload = {'queries': [{'question': question, 'knowledgeBaseIds': ids}], 'rewrite': False,
                       'retrievalMode': 'HYBRID' if step['label'] == 'A' else 'HYBRID_IDENTIFIER',
                       'contextTokenBudget': 6000}
            if previous_start is not None:
                time.sleep(max(0, .65 - (time.perf_counter() - previous_start)))
            began = time.perf_counter()
            previous_start = began
            try:
                response = requests.post(BASE_URL + '/api/knowledgebase/evaluation/retrieval',
                                         json=payload, timeout=(10, 55))
                value = response.json()
                write_json(out / f'{index:03d}.json', {'request': payload, 'httpStatus': response.status_code,
                    'capturedAt': utc_now(), 'response': value})
                if response.status_code != 200 or value.get('code') != 200:
                    raise ValueError('Actual API request failed')
                items = value['data']['items']
                if len(items) != 1 or items[0]['question'] != question:
                    raise ValueError('Unexpected question response')
                item = items[0]
                if item['vectorSearchCalls'] != 1 or item['contextTokenEstimate'] > 6000:
                    raise ValueError('Unexpected call count or budget')
                if signature(item) != expected[step['label'], question]:
                    raise ValueError('Actual selection/candidate signature differs from old reference')
                record = {**step, 'status': 'success', 'itemElapsedMs': item['elapsedMs'],
                          'clientElapsedMs': (time.perf_counter() - began) * 1000,
                          'selectionMatchesOld': True, 'capturedAt': utc_now()}
                records.append(record)
                stream.write(json.dumps(record) + '\n')
                stream.flush()
            except (requests.RequestException, ValueError, KeyError) as error:
                write_json(out / 'failure.json', {'index': index, 'errorType': type(error).__name__,
                    'message': str(error), 'automaticRetry': False, 'fullPlanDenominatorRetained': True})
                break
            if (index + 1) % 27 == 0:
                print(json.dumps({'completedRequests': len(records), 'plannedRequests': len(plan)}), flush=True)
    result = {'startedAt': started, 'finishedAt': utc_now(), 'plannedRequests': len(plan),
        'completedRequests': len(records), 'missing': len(plan) - len(records),
        'uniqueQuestions': len(gold.cases), 'repetitions': 4, 'qualitySamplesAdded': 0,
        'selectionMatchesOldForCompletedRequests': True, 'actualUsageNotExposedByEndpoint': True,
        'scope': 'same local session, old and new scanner have same selection semantics; not cross-day or production latency'}
    if len(records) == len(plan):
        for label in ['A', 'B']:
            values = [x['itemElapsedMs'] for x in records if x['label'] == label]
            result[label] = {'requests': len(values), 'p50Ms': statistics.median(values), 'p95Ms': p95(values)}
        result['p95IncreasePercent'] = (result['B']['p95Ms'] / result['A']['p95Ms'] - 1) * 100
        result['sessionLocalP95CostGatePassed'] = result['p95IncreasePercent'] <= 10
        clusters = {case_id: [x for x in records if x['caseId'] == case_id] for case_id in gold.cases}
        brng = random.Random(61004)
        ratios = []
        for _ in range(1000):
            sample = [x for case_id in brng.choices(list(clusters), k=len(clusters)) for x in clusters[case_id]]
            ratios.append((p95([x['itemElapsedMs'] for x in sample if x['label'] == 'B'])
                         / p95([x['itemElapsedMs'] for x in sample if x['label'] == 'A']) - 1) * 100)
        ratios.sort()
        result['queryClusterP95Increase95PercentInterval'] = [ratios[24], ratios[974]]
    after_provider = provider_snapshot()
    write_json(out / 'provider-after.json', after_provider)
    result['providerUnchanged'] = {k: v for k, v in before_provider.items() if k != 'capturedAt'} == {
        k: v for k, v in after_provider.items() if k != 'capturedAt'}
    after_index = index_state(ids)
    write_json(out / 'index-after.json', after_index)
    result['indexUnchanged'] = before_index == after_index
    result['sourceUnchanged'] = all(digest(ROOT / path) == item['sha256'] for path, item in manifest.items())
    write_json(out / 'summary.json', result)
    print(json.dumps(result), flush=True)
    if len(records) != len(plan) or not all(result[k] for k in ['providerUnchanged', 'indexUnchanged', 'sourceUnchanged']):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
