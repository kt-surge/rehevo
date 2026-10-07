"""Read fixed calibration expectations and actual guarded outputs, never fix a score."""
import argparse
from collections import defaultdict
import json

from citation_judge_v2 import (RUN, isolated_payload, read, sha, validate_isolated,
                               validate_partition, write)


def main(name):
    plan = read(RUN / 'judge-plan.json')
    if sha(RUN / 'frozen-inputs.json') != plan['inputsSha256']:
        raise ValueError('Input drift')
    rows = read(RUN / 'frozen-inputs.json')
    reviewed = []
    totals = defaultdict(lambda: defaultdict(int))
    calibration = []
    for row in rows:
        directory = RUN / 'calls'
        stage_files = {stage: directory / (row['rowId'] + '-' + stage + '.validated.json')
                       for stage in plan['stages']}
        recovery_used = False
        if row['rowId'] == 'cal-missing-proxy-condition' and not stage_files['coverage'].is_file():
            recovery = RUN / 'explicit-recovery-probe'
            candidate = recovery / 'cal-missing-proxy-condition-coverage-recovery.validated.json'
            if candidate.is_file():
                recovery_plan = read(recovery / 'recovery-plan.json')
                prefix = directory / (row['rowId'] + '-coverage')
                if sha(prefix.with_suffix('.request.json')) != recovery_plan['originalRequestSha256'] or \
                        sha(prefix.with_suffix('.payload.json')) != recovery_plan['originalPayloadSha256']:
                    raise ValueError('Recovery probe original request or payload drift')
                stage_files['coverage'] = candidate
                recovery_used = True
        complete = all(path.is_file() for path in stage_files.values())
        if not complete:
            reviewed.append(dict(rowId=row['rowId'], complete=False))
            continue
        partition = read(stage_files['partition'])
        raw_partition = dict(units=[dict(unitId=unit['unitId'], parts=[
            dict(text=p['text'], kind=p['kind']) for p in partition['parts'] if p['unitId'] == unit['unitId']])
            for unit in row['extraction']['units']])
        if validate_partition(row, raw_partition) != partition:
            raise ValueError('Partition no longer matches exact input')
        verdicts = {}
        for stage in ('context', 'edges', 'coverage'):
            payload = isolated_payload(stage, row, partition)
            verdicts[stage] = validate_isolated(stage, payload, read(stage_files[stage]))
        claims = verdicts['context']['claims']
        edges = verdicts['edges']['edges']
        coverage = verdicts['coverage']
        item = dict(rowId=row['rowId'], complete=True, literalGuardsPassed=True,
            explicitRecoveryUsed=recovery_used,
            actualCitations=row['extraction']['actualCitationOccurrences'],
            technicalClaims=len(claims), allClaimsSupported=bool(claims) and all(c['supported'] for c in claims),
            edgeSupported=[edge['supported'] for edge in edges],
            knownFactsAllCovered=bool(coverage['facts']) and all(f['covered'] for f in coverage['facts']),
            missingRefused=bool(coverage['missing']) and all(m['refused'] for m in coverage['missing']),
            verdicts=verdicts)
        if row['cohort'] == 'calibration':
            comparisons = {key: dict(expected=value, actual=item[key], passed=value == item[key])
                           for key, value in row['expected'].items()}
            item['calibration'] = comparisons
            item['calibrationPassed'] = all(c['passed'] for c in comparisons.values())
            calibration.append(item)
        else:
            t = totals[row['arm']]
            t['answers'] += 1
            t['technicalParts'] += len(claims)
            t['supportedTechnicalParts'] += sum(c['supported'] for c in claims)
            t['literalCitationOccurrences'] += item['actualCitations']
            t['citationPartEdges'] += len(edges)
            t['supportedCitationPartEdges'] += sum(e['supported'] for e in edges)
            t['requiredFacts'] += len(coverage['facts'])
            t['coveredRequiredFacts'] += sum(f['covered'] for f in coverage['facts'])
            t['knownRequirementsRefused'] += sum(f['disposition'] == 'refused' for f in coverage['facts'])
            t['missingRequirements'] += len(coverage['missing'])
            t['missingRequirementsRefused'] += sum(m['refused'] for m in coverage['missing'])
        reviewed.append(item)
    all_calibrated = len(calibration) == 8 and all(c['calibrationPassed'] for c in calibration)
    all_dev = sum(item.get('complete', False) for item in reviewed if item['rowId'].startswith('dev-')) == 12
    results = [read(path) for directory in (RUN / 'calls', RUN / 'explicit-recovery-probe')
               for path in directory.glob('*.result.json')]
    imported = read(RUN / 'imported-partition-provenance.json')
    usage = dict(newExternalCalls=len(results),
        knownNewTotalTokens=sum(r['usage']['total_tokens'] for r in results if r.get('usage')),
        newCallsWithUnknownUsage=sum(r.get('usage') is None for r in results),
        importedPartitionTokens=sum(r['usage']['total_tokens'] for r in imported),
        importedCallsNotNew=len(imported))
    usage['explicitRecoveryCalls'] = sum(item.get('explicitRecoveryUsed', False) for item in reviewed)
    summary = dict(scope='same-model exposed-dev custom literal-part diagnostic; not RAGAS, human review or heldout quality',
        calibrationComplete=len(calibration), calibrationTotal=8, calibrationAllPassed=all_calibrated,
        allExposedDevComplete=all_dev, defaultAdoption='HOLD', usage=usage, rawDiagnosticCounts=dict(totals),
        metricsEligibleForDevelopmentDiagnostic=all_calibrated and all_dev,
        formalQualityOrResumeMetricsEligible=False,
        caveats=['Partition kind and semantic completeness still need source review; exact characters alone do not prove atomicity',
                 'Same generator and judge, prior answers already exposed, no independent human review',
                 'Citation edge checks cover each part against one literal source; collective entailment/counterfactual citation recall not measured',
                 'No new generation, actual retrieval, browser performance or formal fresh test in this run'],
        rows=reviewed)
    write(RUN / name, summary)
    print(json.dumps({k: v for k, v in summary.items() if k not in ('rows', 'caveats')}, ensure_ascii=False))
    for item in calibration:
        if not item['calibrationPassed']:
            print(json.dumps(dict(rowId=item['rowId'], failures={key: c for key, c in item['calibration'].items() if not c['passed']}), ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('outputName')
    args = parser.parse_args()
    if '/' in args.outputName or '\\' in args.outputName or not args.outputName.endswith('.json'):
        parser.error('Use a new simple JSON filename')
    main(args.outputName)
