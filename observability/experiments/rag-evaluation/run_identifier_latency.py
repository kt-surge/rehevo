"""Randomized paired session-local latency follow-up; fixed quality policy/Gold.

Repeated requests are performance observations, not new quality samples. Stop on
first failed response; retain the full planned denominator and never auto-retry.
"""
import json
import math
import random
import statistics
import time
from datetime import datetime, timezone

import requests

from fact_gold import digest, load_gold
from ingest_primary_dev import BASE_URL, provider_snapshot, write_json
from prepare_identifier_sources import PACK, ROOT, verify_strategy

RUN = ROOT/'observability/experiments/rag-evaluation/runs/identifier-selection-test-20261002-r1'


def now():return datetime.now(timezone.utc).isoformat()
def p95(values):return sorted(values)[math.ceil(len(values)*0.95)-1]


def main():
    strategy=verify_strategy()
    gold=load_gold(PACK/'manifest.json','agent_verified')
    out=RUN/'paired-latency-r2';out.mkdir(exist_ok=False)
    ids=[x['knowledgeBaseId'] for x in json.loads((RUN/'ingestion.manifest.json').read_text(encoding='utf-8'))['documents']]
    rng=random.Random(61002)
    plan=[]
    for round_index in range(4):
        case_ids=list(gold.cases);rng.shuffle(case_ids)
        for case in case_ids:
            labels=['A','B'];rng.shuffle(labels)
            for label in labels:plan.append({'round':round_index,'caseId':case,'label':label})
    write_json(out/'request-plan.json',{'seed':61002,'plannedRequests':len(plan),'rounds':4,'uniqueQuestions':len(gold.cases),
        'modes':{'A':'HYBRID','B':'HYBRID_IDENTIFIER'},'ids':ids,'contextTokenBudget':6000,
        'qualityPolicyChanged':False,'newQualitySamples':0,'goldManifestSha256':digest(gold.manifest_path),
        'strategySha256':strategy,'randomizedWithinQueryPairs':True,'independentTimeWindows':False,
        'minimumRequestStartGapSeconds':0.65,'existingApiLimit':'2 requests per second; unchanged',
        'previousAttempt':'r1 stopped on real code 8001; records preserved; not resumed or retried',
        'scope':'four sequential passes in one local session; not cross-session production latency'})
    write_json(out/'provider-before.json',provider_snapshot())
    records=[]
    started=now();previous_start=None
    for index,step in enumerate(plan):
        payload={'queries':[{'question':gold.cases[step['caseId']]['question'],'knowledgeBaseIds':ids}],
                 'rewrite':False,'retrievalMode':'HYBRID' if step['label']=='A' else 'HYBRID_IDENTIFIER',
                 'contextTokenBudget':6000}
        if previous_start is not None:
            time.sleep(max(0,0.65-(time.perf_counter()-previous_start)))
        began=time.perf_counter();previous_start=began
        try:
            response=requests.post(BASE_URL+'/api/knowledgebase/evaluation/retrieval',json=payload,timeout=(10,55))
            value=response.json()
            write_json(out/f'{index:03d}.json',{'request':payload,'httpStatus':response.status_code,
                        'capturedAt':now(),'response':value})
            if response.status_code!=200 or value.get('code')!=200:raise ValueError('Actual request failed')
            items=value['data']['items']
            if len(items)!=1 or items[0]['question']!=gold.cases[step['caseId']]['question']:
                raise ValueError('Wrong question response')
            item=items[0]
            if item['vectorSearchCalls']!=1 or item['contextTokenBudget']!=6000 or item['contextTokenEstimate']>6000:
                raise ValueError('Unexpected call/budget')
            records.append({**step,'status':'success','itemElapsedMs':item['elapsedMs'],
                            'clientElapsedMs':(time.perf_counter()-began)*1000,'capturedAt':now()})
        except (requests.RequestException,ValueError,KeyError) as error:
            write_json(out/'failure.json',{'index':index,'errorType':type(error).__name__,'message':str(error),
                       'automaticRetry':False,'fullPlanDenominatorRetained':True});break
        if (index+1)%54==0:print(json.dumps({'completedRequests':len(records),'plannedRequests':len(plan)}),flush=True)
    (out/'records.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in records),encoding='utf-8')
    result={'plannedRequests':len(plan),'completedRequests':len(records),'missing':len(plan)-len(records),
            'startedAt':started,'finishedAt':now(),'uniqueQuestions':len(gold.cases),'rounds':4,
            'qualitySampleCountUnchanged':True,'notProductionOrCrossSessionLatency':True}
    if len(records)==len(plan):
        for label in ['A','B']:
            values=[x['itemElapsedMs'] for x in records if x['label']==label]
            result[label]={'requests':len(values),'medianMs':statistics.median(values),'p95Ms':p95(values)}
        result['p95IncreasePercent']=(result['B']['p95Ms']/result['A']['p95Ms']-1)*100
        result['p50IncreasePercent']=(result['B']['medianMs']/result['A']['medianMs']-1)*100
        result['sessionLocalP95CostGatePassed']=result['p95IncreasePercent']<=10
        # Bootstrap entire query clusters, retaining all repetitions and both
        # labels together. This is within-session uncertainty, not independent
        # production days or a new quality test.
        clusters={cid:[x for x in records if x['caseId']==cid] for cid in gold.cases}
        brng=random.Random(61003);ratios=[]
        for _ in range(1000):
            sample=[x for cid in brng.choices(list(clusters),k=len(clusters)) for x in clusters[cid]]
            av=[x['itemElapsedMs'] for x in sample if x['label']=='A'];bv=[x['itemElapsedMs'] for x in sample if x['label']=='B']
            ratios.append((p95(bv)/p95(av)-1)*100)
        ratios.sort();result['queryClusterP95Increase95PercentInterval']=[ratios[24],ratios[974]]
    after=provider_snapshot();write_json(out/'provider-after.json',after)
    before=json.loads((out/'provider-before.json').read_text(encoding='utf-8'))
    if {k:v for k,v in before.items() if k!='capturedAt'}!={k:v for k,v in after.items() if k!='capturedAt'}:
        raise ValueError('Actual provider configuration changed; retain records')
    result['providerUnchanged']=True;result['strategyUnchanged']=verify_strategy()==strategy
    write_json(out/'summary.json',result)
    print(json.dumps(result),flush=True)


if __name__=='__main__':main()
