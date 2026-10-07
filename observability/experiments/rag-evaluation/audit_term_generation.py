"""Independent local audit of the completed one-factor development study; no model calls."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = HERE / 'runs/term-generation-dev-20261005-r2'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(Path('\\\\?\\' + str(path.resolve())).read_bytes()).hexdigest()


def main():
    output = RUN / 'final-audit.json'
    if output.exists():
        raise ValueError('Preserve existing audit')
    plan = read(RUN / 'inputs.json')
    results, prompts, begins = {}, {}, {}
    for directory in ('preflight', 'remaining'):
        for line in (RUN / directory / 'events.jsonl').read_text(encoding='utf-8').splitlines():
            item = json.loads(line)
            if item['kind'] == 'failure':
                raise ValueError('Provider failure: do not silently exclude it')
            target = begins if item['kind'] == 'begin' else results
            if item['index'] in target:
                raise ValueError('Duplicate scheduled call')
            target[item['index']] = item
            if item['kind'] == 'begin':
                prompts[item['index']] = read(RUN / directory / item['prompt'])
    if set(results) != set(range(12)) or set(begins) != set(range(12)):
        raise ValueError('Full fixed denominator is incomplete')
    source_checks = []
    for directory in ('baseline-source', 'driver-source'):
        manifest = read(RUN / directory / 'manifest.json')
        for row in manifest['sources']:
            frozen = RUN / directory / row['file']
            original_name = row.get('original', row['file'])
            original = ROOT / original_name
            if digest(frozen) != row['sha256'] or digest(original) != row['sha256']:
                raise ValueError('Frozen or executed source changed: ' + original_name)
            source_checks.append(original_name)
    pairs = []
    known_tokens = dict(current=0, grounded=0)
    characters = dict(current=0, grounded=0)
    definitions = dict(current=0, grounded=0)
    for case in plan['cases']:
        indices = {step['arm']: i for i, step in enumerate(plan['schedule']) if step['caseId'] == case['id']}
        if set(indices) != {'current', 'grounded'}:
            raise ValueError('Unpaired case')
        a, b = [prompts[indices[arm]] for arm in ('current', 'grounded')]
        if a['system'].count(plan['oldTerminologyRule']) != 1:
            raise ValueError('Expected one-factor rule')
        if a['system'].replace(plan['oldTerminologyRule'], plan['candidateTerminologyRule']) != b['system']:
            raise ValueError('System difference exceeds frozen rule')
        if {k:v for k,v in a.items() if k != 'system'} != {k:v for k,v in b.items() if k != 'system'}:
            raise ValueError('User context, evidence IDs or model options changed')
        user = a['user'].replace('\r\n', '\n')
        if case['question'] not in user or any(e['body'].replace('\r\n', '\n') not in user for e in case['contexts']):
            raise ValueError('Supplied public input differs')
        for arm, index in indices.items():
            item = results[index]
            if item['caseId'] != case['id'] or item['arm'] != arm or item['usage']['totalTokens'] <= 0:
                raise ValueError('Result/usage mismatch')
            if item['citationValidation']['unknownEvidenceIds']:
                raise ValueError('Unknown citation ID')
            known_tokens[arm] += item['usage']['totalTokens']
            characters[arm] += len(item['answer'])
            definitions[arm] += int('Pending Entries List' in item['answer'])
        pairs.append(dict(caseId=case['id'], indices=indices, exactUserEquality=True,
            oneSystemSentenceChanged=True, identicalCapturedOptions=True,
            exactQuestionAndBodySupply=True, answers={arm:results[i] for arm,i in indices.items()}))
    # A source-author diagnostic, not a calibrated automated completeness or faithfulness score.
    noack = next(x for x in pairs if x['caseId'] == 'primary-dev-rd-04')
    answers = noack['answers']
    if any('Pending Entries List' in e['body'] for e in next(c for c in plan['cases'] if c['id']=='primary-dev-rd-04')['contexts']):
        raise ValueError('The frozen NOACK development excerpt actually contains the definition')
    review = dict(reviewerType='source-author Agent', humanReviewed=False,
        caseId='primary-dev-rd-04', currentIncludesReliabilityLossCondition='可靠' in answers['current']['answer'] and '丢' in answers['current']['answer'],
        candidateIncludesReliabilityLossCondition='可靠' in answers['grounded']['answer'] and '丢' in answers['grounded']['answer'],
        exactSourceCondition='cases where reliability is not a requirement and the occasional message loss\nis acceptable.',
        conclusion='Both answers add an unprovided PEL expansion; candidate omits the stated applicability condition. Reject this candidate.',
        limitation='Manual source-author review of exposed excerpts; no formal necessary-fact percentage or context/citation support score.')
    value = dict(cases=6, actualGenerationCalls=12, unknownUsageRequests=0,
        knownTokens=known_tokens, totalKnownTokens=sum(known_tokens.values()),
        answerCharacters=characters, answersWithUnprovidedPelExpansion=definitions,
        unknownCitationIds=0, citationIdCheckIsSemanticSupport=False,
        sourceFilesVerified=len(source_checks), sourceChecks=source_checks, pairs=pairs,
        sourceAuthorReview=review, candidateAdopted=False, productionDefaultsChanged=False,
        sharedCitationEnabled=True, wireCapture=False, finishReasonRecorded=False,
        formalQualityClaim=False, latencyBenefitClaim=False)
    output.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:value[k] for k in ['cases','actualGenerationCalls','totalKnownTokens','sourceFilesVerified','candidateAdopted']}))


if __name__ == '__main__':
    main()
