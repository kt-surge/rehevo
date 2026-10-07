"""Keep judge decisions separate from source quote checks and developer review."""
from collections import defaultdict
import hashlib
import json
from pathlib import Path

RUN = Path(__file__).resolve().parent / 'runs/citation-chain-20261005-r1'


def normalized(text):
    return ' '.join(text.split())


def main():
    plan=json.loads((RUN/'inputs.json').read_text(encoding='utf-8'))
    cases={c['id']:c for c in plan['cases']}
    rows=[]
    for directory in ['preflight-r2','remaining']:
        for line in (RUN/directory/'events.jsonl').read_text(encoding='utf-8').splitlines():
            row=json.loads(line)
            if row['kind']=='result': row['directory']=directory; rows.append(row)
    rows.sort(key=lambda r:r['index'])
    judges={}
    for path in RUN.glob('judge-*/*.result.json'):
        result=json.loads(path.read_text(encoding='utf-8')); judges[result['index']]=result
    totals={arm:defaultdict(int) for arm in ['plain','numbered']}
    output=[]; audit=[]
    for row in rows:
        case=cases[row['caseId']]; judge=judges[row['index']]; report=judge['parsed']; totals[row['arm']]['answers']+=1
        if not judge['successful']: raise ValueError('Cannot score failed judge')
        contexts={'E'+str(i+1):context['body'] for i,context in enumerate(case['contexts'])}
        for i,claim in enumerate(report['claims']):
            totals[row['arm']]['claims']+=1
            totals[row['arm']]['supportedClaims']+=int(claim['supported'])
            cited=claim['citationIds']; quote=normalized(claim['supportQuote'])
            quote_matches=bool(quote) and any(quote in normalized(text) for text in contexts.values())
            if claim['supported'] and not quote_matches:
                audit.append(dict(index=row['index'],kind='supported_claim_without_exact_context_quote',claim=i,text=claim['text']))
            if cited:
                totals[row['arm']]['citedClaims']+=1
                totals[row['arm']]['supportedCitedClaims']+=int(claim['citedEvidenceSupports'])
                quote_in_cited=bool(quote) and any(quote in normalized(contexts.get(e,'')) for e in cited)
                if claim['citedEvidenceSupports'] and not quote_in_cited:
                    audit.append(dict(index=row['index'],kind='citation_support_without_matching_quote',claim=i,text=claim['text']))
        actual_ids=[f['factId'] for f in report['facts']]
        if set(actual_ids)!=set(case['referenceFactIds']) or len(actual_ids)!=len(set(actual_ids)):
            audit.append(dict(index=row['index'],kind='reference_fact_set_mismatch',expected=case['referenceFactIds'],actual=actual_ids))
        for fact in report['facts']:
            totals[row['arm']]['requiredFacts']+=1
            totals[row['arm']]['coveredRequiredFacts']+=int(fact['covered'])
            if fact['covered'] and (not fact['answerQuote'] or normalized(fact['answerQuote']) not in normalized(row['answer'])):
                audit.append(dict(index=row['index'],kind='coverage_without_exact_answer_quote',factId=fact['factId']))
        if case['answerable']:
            totals[row['arm']]['answerable']+=1; totals[row['arm']]['falseWholeRefusals']+=int(report['refusedAll'])
        else:
            totals[row['arm']]['unanswerable']+=1; totals[row['arm']]['correctWholeRefusals']+=int(report['refusedAll'])
        if case.get('missingRequirement'):
            totals[row['arm']]['partialCases']+=1
            totals[row['arm']]['partialMissingAcknowledged']+=int(report['missingInformationAcknowledged'])
        output.append(dict(**row, judge=report))
    parity=[]
    for case_id in cases:
        pair=[r for r in rows if r['caseId']==case_id]
        if len(pair)!=2: raise ValueError('Incomplete pair')
        for r in pair:
            p=json.loads((RUN/r['directory']/f"{r['index']:02d}-prompt.json").read_text(encoding='utf-8'))
            expected='\n\n---\n\n'.join((f'[E{i+1}]\n' if r['arm']=='numbered' else '')+c['body']
                for i,c in enumerate(cases[case_id]['contexts']))
            if expected not in p['user'].replace('\r\n','\n'):
                raise ValueError('Actual supplied body does not match frozen input')
        parity.append(dict(caseId=case_id,bodyAndOrderUnchangedAfterSharedRendererNewlineConversion=True,
            renderer='StringTemplate converts LF to CRLF equally in both arms; raw prompts preserved'))
    metrics={}
    for arm, t in totals.items():
        metrics[arm]=dict(t)
        metrics[arm].update(faithfulnessJudge=t['supportedClaims']/t['claims'] if t['claims'] else None,
            requiredFactCoverageJudge=t['coveredRequiredFacts']/t['requiredFacts'] if t['requiredFacts'] else None,
            citationSupportJudge=t['supportedCitedClaims']/t['citedClaims'] if t['citedClaims'] else None)
    summary=dict(scope='six exposed development cases; custom fixed same-model judge, not RAGAS or formal heldout',
        metrics=metrics,parity=parity,quoteAudit=audit,reviewStatus='requires source-author review; not human verified',
        generationUsage=dict(inputTokens=sum(r['usage']['inputTokens'] for r in rows),
            outputTokens=sum(r['usage']['outputTokens'] for r in rows),totalTokens=sum(r['usage']['totalTokens'] for r in rows)),
        judgeUsage=dict(inputTokens=sum(j['usage']['prompt_tokens'] for j in judges.values()),
            outputTokens=sum(j['usage']['completion_tokens'] for j in judges.values()),
            totalTokens=sum(j['usage']['total_tokens'] for j in judges.values())),
        successfulGenerations=len(rows),successfulJudges=len(judges),defaultAdoption='HOLD')
    (RUN/'scored-answers.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
    (RUN/'preflight-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))


if __name__=='__main__': main()
