"""Development-only offline projection using previously frozen actual candidates.

Selection receives question/text/rank/token estimates only. Gold is loaded solely
by the scoring caller afterwards. No API, embedding, generation or latency claim.
The two predetermined policies differ only in preferring a repeated method-detail
heading/signature over an ordinary exact identifier mention.
"""
import argparse
import hashlib
import json
import re
import shutil
import time
from pathlib import Path

from fact_gold import load_gold, load_variant, score


TOKEN = re.compile(r"@?[A-Za-z][A-Za-z0-9_]*(?:[.-][A-Za-z][A-Za-z0-9_]*)*")


def anchors(question):
    if not question or len(question) > 512:
        return []
    found = {}
    for match in TOKEN.finditer(question):
        full = match.group()
        term = full.rsplit('.', 1)[-1].lstrip('@')
        if len(term) < 3 or len(term) > 100:
            continue
        priority = (3 if '.' in full else 2 if full.startswith('@') or '_' in full
                    or full.isupper() else 1)
        found[term] = max(priority, found.get(term, 0))
    if len(found) > 8:
        return []
    return [term for term, _ in sorted(found.items(), key=lambda pair: -pair[1])]


def exact_mention(text, term):
    return re.search(r"(?<![A-Za-z0-9_])" + re.escape(term) + r"(?![A-Za-z0-9_])", text) is not None


def detail_heading(text, term):
    # Parsed API details repeat the heading before a declaration. Method summary
    # rows normally contain the name once; all matched text is retained unchanged.
    return re.search(r"(?:^|\n)" + re.escape(term) + r"\s*\n[^\n]{0,120}"
                     r"[\s\S]{0,180}?\b" + re.escape(term) + r"\s*\(", text) is not None


def select(question, candidates, texts, tokens, prefer_body, top_k=8, budget=6000):
    terms = anchors(question)
    reserved = []
    reasons = []
    for term in terms:
        matched = [cid for cid in candidates if exact_mention(texts[cid], term)]
        if not matched:
            continue
        detailed = [cid for cid in matched if detail_heading(texts[cid], term)] if prefer_body else []
        chosen = (detailed or matched)[0]
        if chosen not in reserved:
            reserved.append(chosen)
            reasons.append({'identifier': term, 'chunkId': chosen, 'detailHeading': bool(detailed)})
        if len(reserved) == min(4, top_k // 2):
            break
    ordered = reserved + [cid for cid in candidates if cid not in reserved]
    selected, used = [], 0
    for cid in ordered:
        if len(selected) >= top_k:
            break
        if used + tokens[cid] <= budget:
            selected.append(cid)
            used += tokens[cid]
    return selected, {'anchors': terms, 'reserved': reasons, 'contextTokenEstimate': used}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source, out = args.input, args.output
    out.mkdir(parents=True, exist_ok=False)
    (out/'inputs').mkdir()
    for name in ['baseline-chunk-variant.json', 'actual-chunks.json', 'chunk-token-estimates.tsv',
                 'a-budget6000-r1/records.jsonl', 'a-budget6000-r1/scores.json']:
        path = out/'inputs'/Path(name).name
        # Two score/record names are unique in this small frozen input set.
        shutil.copyfile(source/name, path)
    shutil.copytree(source/'frozen-input', out/'gold')
    script = Path(__file__)
    shutil.copyfile(script, out/'diagnose_identifier_selection.py')
    config = {'policies': ['mention-reservation', 'detail-reservation'], 'topK': 8,
              'budget': 6000, 'maxReserved': 4, 'maxAnchors': 8,
              'frozenBeforeScoring': True, 'newApiCalls': 0, 'split': 'dev',
              'scriptSha256': hashlib.sha256(script.read_bytes()).hexdigest(),
              'notProductionOrHeldoutEvidence': True}
    (out/'policy-freeze.json').write_text(json.dumps(config, indent=2)+'\n', encoding='utf-8')
    chunks = json.loads((source/'actual-chunks.json').read_text(encoding='utf-8'))
    texts = {row['chunk_id']: row['text'] for row in chunks}
    tokens = {line.split('\t')[0]: int(line.split('\t')[1]) for line in
              (source/'chunk-token-estimates.tsv').read_text(encoding='utf-8').splitlines()}
    previous = [json.loads(line) for line in
                (source/'a-budget6000-r1/records.jsonl').read_text(encoding='utf-8').splitlines()]
    questions = {row['id']: row['question'] for row in map(json.loads,
                 (out/'gold/dev.jsonl').read_text(encoding='utf-8').splitlines())}
    results = {}
    for name, body in [('mention-reservation', False), ('detail-reservation', True)]:
        records, traces = [], []
        for before in previous:
            began = time.perf_counter()
            context, trace = select(questions[before['caseId']], before['candidateChunkIds'], texts, tokens, body)
            elapsed = (time.perf_counter() - began) * 1000
            assert len(context) <= 8 and trace['contextTokenEstimate'] <= 6000
            records.append({'caseId': before['caseId'], 'status': before['status'],
                            'candidateChunkIds': before['candidateChunkIds'],
                            'contextChunkIds': context, 'elapsedMs': elapsed})
            traces.append({'caseId': before['caseId'], **trace})
        results[name] = records
        (out/(name+'-records.jsonl')).write_text(''.join(json.dumps(x, ensure_ascii=False)+'\n'
                                                for x in records), encoding='utf-8')
        (out/(name+'-selection-traces.json')).write_text(json.dumps(traces, ensure_ascii=False, indent=2)+'\n',
                                                        encoding='utf-8')
    # Selection is complete before loading evidence requirements for scoring.
    gold = load_gold(out/'gold/manifest.json', 'agent_verified')
    variant = load_variant(out/'inputs/baseline-chunk-variant.json', gold)
    baseline = score(gold, variant, previous, 'dev')
    summary = {}
    for name, records in results.items():
        report = score(gold, variant, records, 'dev')
        report.update(kind='offline-development-projection', newApiCalls=0,
                      latencyScope='local selection only; never actual retrieval or provider latency')
        (out/(name+'-scores.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        old = {row['caseId']: row for row in baseline['rows']}
        changes = [{'caseId':row['caseId'], 'before':old[row['caseId']]['contextAllRequired'],
                    'after':row['contextAllRequired']} for row in report['rows']
                   if row['answerable'] and row['contextAllRequired'] != old[row['caseId']]['contextAllRequired']]
        summary[name] = {'allRequired':sum(row['contextAllRequired'] for row in report['rows'] if row['answerable']),
                         'answerable':report['answerable'], 'changes':changes,
                         'conditions':sum(req['contextSatisfied'] for row in report['rows'] if row['answerable']
                                          for req in row['requirementResults']),
                         'requiredConditions': report['requiredConditions']}
    (out/'comparison.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
