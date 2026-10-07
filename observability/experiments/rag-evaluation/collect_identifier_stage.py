"""Validate actual identifier A/B traces, then seal reproducible stage evidence."""
import argparse
import json
import shutil
import statistics
import sys
from datetime import datetime
from pathlib import Path

import requests

from diagnose_identifier_selection import select
from experimental_index_snapshot import assert_scope, current, vectors_hash
from fact_gold import digest, load_gold, load_variant, score
from ingest_primary_dev import BASE_URL, ROOT, docker_json, write_json
from prepare_identifier_sources import DEV_RUN, FROZEN, PACK, verify_strategy

TEST_RUN = ROOT/'observability/experiments/rag-evaluation/runs/identifier-selection-test-20261002-r1'
OLD_DEV = ROOT/'observability/experiments/rag-evaluation/runs/expanded-dev-20261002-r1'


def read(path):return json.loads(path.read_text(encoding='utf-8-sig'))
def rows(path):return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines()]
def dt(value):return datetime.fromisoformat(value.replace('Z','+00:00'))


def summarize_run(run, gold_path, split):
    gold=load_gold(gold_path,'agent_verified')
    variant=load_variant(run/'baseline-chunk-variant.json',gold)
    texts={x['chunk_id']:x['text'] for x in read(run/'actual-chunks.json')}
    tokens={line.split('\t')[0]:int(line.split('\t')[1]) for line in
            (run/'chunk-token-estimates.tsv').read_text(encoding='utf-8').splitlines()}
    a_dir,b_dir=run/'a-hybrid6000-r1',run/'b-identifier6000-r1'
    for name in ['source-hashes.json','running-provider-config.json','effective-retrieval-config.json']:
        first,second=read(a_dir/name),read(b_dir/name)
        if name=='running-provider-config.json':
            first={k:v for k,v in first.items() if k!='capturedAt'}
            second={k:v for k,v in second.items() if k!='capturedAt'}
        if first!=second:raise ValueError('A/B configuration/source mismatch: '+name)
    a,b=rows(a_dir/'records.jsonl'),rows(b_dir/'records.jsonl')
    cases={k:c for k,c in gold.cases.items() if c['split']==split}
    if set(x['caseId'] for x in a)!=set(cases) or set(x['caseId'] for x in b)!=set(cases):
        raise ValueError('Actual cases missing; preserve failed run')
    a_by={x['caseId']:x for x in a}
    reservations={}
    for label,records in [('A',a),('B',b)]:
        for x in records:
            if x['status']!='success' or x['vectorSearchCalls']!=1 or x['focusedQueries']:
                raise ValueError('Unexpected failure or extra vector call')
            if len(x['contextChunkIds'])>8 or len(x['candidateChunkIds'])>20:
                raise ValueError('Count bound exceeded')
            used=sum(tokens[cid] for cid in x['contextChunkIds'])
            if used>6000 or used!=x['contextTokenEstimate'] or x['contextTokenBudget']!=6000:
                raise ValueError('Actual context budget mismatch')
            if label=='A' and x['identifierReservations']:
                raise ValueError('A unexpectedly selected identifiers')
            if label=='B':
                selected,trace=select(cases[x['caseId']]['question'],x['candidateChunkIds'],texts,tokens,True)
                if selected!=x['contextChunkIds']:raise ValueError('Java actual selection differs from frozen projection')
                actual=[{'identifier':item['identifier'],'chunkId':item['vectorDocumentId'],
                         'detailHeading':item['detailHeading']} for item in x['identifierReservations']]
                if actual!=trace['reserved']:raise ValueError('Actual reservation trace mismatch')
                reservations[x['caseId']]=trace
    scores_a=score(gold,variant,a,split);scores_b=score(gold,variant,b,split)
    old={x['caseId']:x for x in scores_a['rows']}
    changes=[{'caseId':x['caseId'],'before':old[x['caseId']]['contextAllRequired'],
              'after':x['contextAllRequired']} for x in scores_b['rows']
             if x['answerable'] and x['contextAllRequired']!=old[x['caseId']]['contextAllRequired']]
    # Check the same B candidates with ordinary HYBRID selection. This local
    # counterfactual isolates selection from any separate embedding rank drift.
    counter=[]
    for x in b:
        selected,used=[],0
        for cid in x['candidateChunkIds']:
            if len(selected)>=8:break
            if used+tokens[cid]<=6000:selected.append(cid);used+=tokens[cid]
        counter.append({**x,'contextChunkIds':selected,'elapsedMs':0})
    counter_score=score(gold,variant,counter,split)
    for field in ['elapsedMedianMs','elapsedP95Ms']:
        counter_score[field]=None
    counter_score['elapsedSamples']=0
    for row in counter_score['rows']:row['elapsedMs']=None
    write_json(run/'same-b-candidates-baseline-selection.json',{'kind':'offline-counterfactual-only',
        'notAnotherActualApiRun':True,'inheritedActualLatencyExcluded':True,
        'scores':counter_score,'records':counter})
    summaries={}
    for name,recs,s in [('A',a,scores_a),('B',b,scores_b)]:
        summaries[name]={'cases':s['cases'],'answerable':s['answerable'],'missing':s['missing'],
            'failures':s['failures'],'candidateAllRequired':sum(x['candidateAllRequired'] for x in s['rows'] if x['answerable']),
            'contextAllRequired':sum(x['contextAllRequired'] for x in s['rows'] if x['answerable']),
            'conditions':sum(y['contextSatisfied'] for x in s['rows'] if x['answerable'] for y in x['requirementResults']),
            'requiredConditions':s['requiredConditions'],'vectorSearchCalls':sum(x['vectorSearchCalls'] for x in recs),
            'contextTokenMedian':statistics.median(x['contextTokenEstimate'] for x in recs),
            'elapsedMedianMs':s['elapsedMedianMs'],'elapsedP95Ms':s['elapsedP95Ms']}
    result={'split':split,'goldManifestSha256':digest(gold.manifest_path),'summaries':summaries,
            'changes':changes,'sameCandidateSetPairs':sum(set(x['candidateChunkIds'])==set(a_by[x['caseId']]['candidateChunkIds']) for x in b),
            'sameCandidateOrderPairs':sum(x['candidateChunkIds']==a_by[x['caseId']]['candidateChunkIds'] for x in b),
            'sameBOriginalSelectionAllRequired':sum(x['contextAllRequired'] for x in counter_score['rows'] if x['answerable']),
            'javaProjectionParityCases':len(b),'allBudgetsVerified':True,
            'latencyScope':'ordered one-pass actual retrieval; no randomized repeated causal latency claim',
            'generationCalled':False,'judgeCalled':False,'refusalEvaluated':False,'sameAgentReviewed':True,
            'notProductionOrIndependentHumanSample':True}
    write_json(run/'comparison.json',result)
    write_json(run/'actual-selection-parity.json',reservations)
    return result


def summarize():
    strategy=verify_strategy()
    a=summarize_run(DEV_RUN,DEV_RUN/'frozen-input/manifest.json','dev')
    b=summarize_run(TEST_RUN,PACK/'manifest.json','test')
    stages={'strategy':dt(read(FROZEN/'strategy-freeze.json')['frozenAt']),
            'firstSource':min(dt(d['retrievedAt']) for d in read(PACK/'sources.manifest.json')['documents']),
            'gold':dt(read(PACK/'manifest.json')['frozenAt']),
            'firstTestResponse':dt(read(TEST_RUN/'a-hybrid6000-r1/batch-000-response.json')['capturedAt'])}
    if not stages['strategy']<stages['firstSource']<stages['gold']<stages['firstTestResponse']:
        raise ValueError('Freeze sequence violated')
    write_json(TEST_RUN/'sequence-evidence.json',{k:v.isoformat() for k,v in stages.items()})
    write_json(TEST_RUN/'sequence-verification.json',{'strictOrder':True,'strategyUnchanged':verify_strategy()==strategy})
    print(json.dumps({'dev':a,'test':b},ensure_ascii=False),flush=True)


def default_check():
    before=rows(OLD_DEV/'a-hybrid-r1/records.jsonl')
    before={x['caseId']:x for x in before}
    gold=load_gold(DEV_RUN/'frozen-input/manifest.json','agent_verified')
    ids=[x['knowledgeBaseId'] for x in read(DEV_RUN/'ingestion.manifest.json')['documents']]
    chosen=['expanded-dev-cross-01','expanded-dev-cross-03','primary-dev-pg-01']
    payload={'queries':[{'question':gold.cases[c]['question'],'knowledgeBaseIds':ids} for c in chosen],'rewrite':False}
    response=requests.post(BASE_URL+'/api/knowledgebase/evaluation/retrieval',json=payload,timeout=(10,55))
    raw={'httpStatus':response.status_code,'request':payload,'response':response.json()}
    write_json(DEV_RUN/'default-check-raw.json',raw)
    value=raw['response']
    if response.status_code!=200 or value.get('code')!=200:raise ValueError('Default check failed; raw retained')
    results=[]
    for case,item in zip(chosen,value['data']['items']):
        if item['vectorSearchCalls']!=1 or item['focusedQueries'] or item['identifierReservations'] \
                or item['contextTokenBudget'] is not None or item['contextTokenEstimate'] is not None:
            raise ValueError('Default semantics changed')
        ids_now=[x['vectorDocumentId'] for x in item['evidence']]
        if ids_now!=[x['vectorDocumentId'] for x in item['candidateEvidence']][:8]:
            raise ValueError('Default selection is not original topK')
        results.append({'caseId':case,'samePriorChunkSet':set(ids_now)==set(before[case]['contextChunkIds']),
                        'samePriorChunkOrder':ids_now==before[case]['contextChunkIds']})
    write_json(DEV_RUN/'default-check.json',{'results':results,'originalSelectionSemantics':True,
                                           'singleVectorCall':True,'noForcedBudgetOrIdentifierSelection':True})
    print(json.dumps({'defaultCheck':results}),flush=True)


def runtime():
    snapshot=current();assert_scope(snapshot)
    expected=read(OLD_DEV/'final-runtime.json')
    original_hash=vectors_hash(snapshot)
    all_docs=docker_json('postgres','psql','-U','postgres','-d','rehevo_opt','-A','-t','-c',
        "SELECT json_agg(json_build_object('id',id,'status',vector_status,'sha',file_hash,'chunks',chunk_count)) FROM knowledge_bases;")
    totals=docker_json('postgres','psql','-U','postgres','-d','rehevo_opt','-A','-t','-c',
        "SELECT json_build_object('vectors',(SELECT count(*) FROM vector_store),'temporaryVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL),'tasks',(SELECT count(*) FROM kb_vector_tasks));")
    groups=docker_json('redis','redis-cli','--json','XINFO','GROUPS','knowledgebase:vectorize:stream')
    groups=[x if isinstance(x,dict) else dict(zip(x[::2],x[1::2])) for x in groups]
    group=next(x for x in groups if x['name']=='vectorize-group')
    all_chunks=docker_json('postgres','psql','-U','postgres','-d','rehevo_opt','-A','-t','-c',
        "SELECT json_agg(row_to_json(t)) FROM (SELECT id::text AS chunk_id,content AS text,metadata,metadata->>'kb_id' AS kb_id,(metadata->>'chunk_index')::int AS chunk_index FROM vector_store) t;")
    lookup={x['chunk_id']:x for x in all_chunks}
    old_test=ROOT/'observability/experiments/rag-evaluation/runs/heldout-focused-20261002-r1'
    old_chunks=read(OLD_DEV/'actual-chunks.json')+read(old_test/'actual-chunks.json')
    unchanged=all(x==lookup.get(x['chunk_id']) for x in old_chunks)
    expected_docs=read(DEV_RUN/'exposed-index-registry/ingestion.manifest.json')['documents'] \
        +read(TEST_RUN/'ingestion.manifest.json')['documents']
    expected_shas={x['knowledgeBaseId']:x['uploadedCanonicalTextSha256'] for x in expected_docs}
    if len(all_docs)!=15 or totals!={'vectors':123,'temporaryVectors':0,'tasks':0} \
            or group['pending']!=0 or group['lag']!=0 or not unchanged \
            or any(x['status']!='COMPLETED' for x in all_docs) \
            or {x['id']:x['sha'] for x in all_docs}!=expected_shas:
        raise ValueError('Unexpected runtime/index/queue state')
    # The strict original four-doc vector-value fingerprint comes from the same
    # snapshot helper used throughout; other exposed docs verify known text/meta.
    original=json.loads((ROOT/'data/local/rehevo-opt-runtime-20261001/original-public-index.json').read_text(encoding='utf-8'))
    if original_hash!=vectors_hash(original):raise ValueError('Original exact vectors changed')
    status=requests.get(BASE_URL+'/actuator/health',timeout=8).json()['status']
    if status!='UP':raise ValueError('Application not healthy')
    result={'health':status,'documents':all_docs,**totals,'redisPending':group['pending'],'redisLag':group['lag'],
            'originalFourExactVectorSha256':original_hash,'originalFourExactVectorsUnchanged':True,
            'priorExposedTextAndMetadataChunks':len(old_chunks),'priorExposedTextAndMetadataUnchanged':unchanged,
            'strategyUnchanged':bool(verify_strategy()),'scope':'retained isolated public corpora for active goal; no production data'}
    for run in [DEV_RUN,TEST_RUN]:write_json(run/'final-runtime.json',result)
    print(json.dumps({k:v for k,v in result.items() if k!='documents'}),flush=True)


def seal():
    verify_strategy()
    runtime()
    sys.path.insert(0,str(ROOT/'observability/experiments/async-reliability'))
    import finalize_vector_generation as secret_checker
    for run in [DEV_RUN,TEST_RUN]:
        if (run/'artifacts.sha256.json').exists():raise ValueError('Already sealed')
        if run==TEST_RUN:shutil.copytree(PACK,run/'frozen-input')
        helpers=run/'helpers';helpers.mkdir()
        for name in ['collect_identifier_stage.py','diagnose_identifier_selection.py','prepare_identifier_sources.py',
                     'build_identifier_gold.py','ingest_identifier_pack.py','run_fact_retrieval.py','fact_gold.py',
                     'align_primary_chunks.py','RehevoChunkTokenCounts.java','token-count.init.gradle',
                     'run_identifier_latency.py','IdentifierSelectionComponentBenchmark.java',
                     'identifier-component.init.gradle']:
            shutil.copyfile(Path(__file__).parent/name,helpers/name)
        for source,target in [('IDENTIFIER_SELECTION_DESIGN_2026-10-02.md','DESIGN.md'),
                              ('IDENTIFIER_SELECTION_RESULTS_2026-10-03.md','RESULTS.md')]:
            shutil.copyfile(Path(__file__).parent/source,run/target)
        if run==TEST_RUN:
            for label in ['r1','r2']:
                shutil.copyfile(ROOT/f'data/local/rehevo-opt-runtime-20261001/identifier-gold-prepare-{label}.log',
                                run/f'gold-preparation-{label}.log')
        files=[p for p in sorted(run.rglob('*')) if p.is_file()]
        secret_checker.RUN=run
        hits=secret_checker.secret_hits()
        if hits:raise ValueError('Sensitive value hits; filenames only: '+str(hits))
        hashes={p.relative_to(run).as_posix():digest(p) for p in files}
        write_json(run/'artifacts.sha256.json',hashes)
        mismatches=[name for name,value in hashes.items() if digest(run/name)!=value]
        write_json(run/'post-freeze-verification.json',{'files':len(hashes),'hashMismatches':mismatches,
                   'sensitiveValueHitFiles':[],'strategyUnchanged':True,'heldoutContextGain':True,
                   'sessionLocalLatencyCostGatePassed':False,
                   'notDefaultEnabled':True,'goalStillActive':True})
        if mismatches:raise ValueError('Sealed hash mismatch')
        print(json.dumps({'run':run.name,'files':len(hashes),'hashMismatches':0,'sensitiveHitFiles':0}),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['summarize','default-check','runtime','seal'])
    args=parser.parse_args()
    {'summarize':summarize,'default-check':default_check,'runtime':runtime,'seal':seal}[args.action]()
