"""Freeze the identifier policy before opening a fresh source/theme test pack."""
import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

import requests
from bs4 import BeautifulSoup

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
DEV_RUN = ROOT / 'observability/experiments/rag-evaluation/runs/identifier-selection-actual-dev-20261002-r1'
FROZEN = DEV_RUN / 'frozen-identifier-r1'
PACK = ROOT / 'data/local/fact-gold-identifier-test-20261002'
SOURCES = [
    ('java21-matcher', 'java-regex-match-state', '21',
     'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Matcher.html', 'main'),
    ('python313-heapq', 'python-heap-algorithms', '3.13',
     'https://docs.python.org/3.13/library/heapq.html', 'div.body'),
    ('python313-bisect', 'python-binary-search-insertion', '3.13',
     'https://docs.python.org/3.13/library/bisect.html', 'div.body'),
]


def verify_strategy():
    manifest = json.loads((FROZEN/'manifest.json').read_text(encoding='utf-8'))
    for path, item in manifest.items():
        if digest(ROOT/path) != item['sha256'] or digest(FROZEN/item['file']) != item['sha256']:
            raise ValueError('Identifier strategy changed after freeze')
    return digest(FROZEN/'strategy-freeze.json')


def freeze():
    DEV_RUN.mkdir(exist_ok=False)
    FROZEN.mkdir()
    paths = sorted((ROOT/'app/src/main/java/interview/guide/modules/knowledgebase').rglob('*.java'))
    paths += [ROOT/'app/src/main/resources/application.yml',
              ROOT/'app/src/test/java/interview/guide/modules/knowledgebase/service/IdentifierEvidenceSelectorTest.java']
    manifest = {}
    for index, path in enumerate(paths):
        short = f'{index:03d}.source'
        shutil.copyfile(path, FROZEN/short)
        manifest[path.relative_to(ROOT).as_posix()] = {'file':short, 'sha256':digest(path)}
    (FROZEN/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    info = {'frozenAt':datetime.now(timezone.utc).isoformat(), 'mode':'HYBRID_IDENTIFIER',
            'defaultMode':'HYBRID', 'topK':8, 'contextTokenBudget':6000,
            'originalQuestionVectorCalls':1, 'maxReservations':4, 'maxAnchors':8,
            'maxQuestionCharacters':512, 'rewrite':False, 'rerank':False,
            'candidateSourceChangedAfterTest':False, 'newSourcesOpened':False,
            'review':'same-Agent self review; no independent reviewer',
            'plannedNewSources':[row[3] for row in SOURCES],
            'limitations':'text heading heuristic, not semantic correctness or full body guarantee'}
    (FROZEN/'strategy-freeze.json').write_text(json.dumps(info,indent=2)+'\n',encoding='utf-8')
    regression = DEV_RUN/'regression-r1'
    regression.mkdir()
    totals = dict(tests=0,failures=0,errors=0,skipped=0)
    suites = []
    for index, path in enumerate(sorted((ROOT/'app/build/test-results/test').glob('TEST-*.xml'))):
        suite = ElementTree.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key,'0'))
        suites.append(suite.get('name'))
        shutil.copyfile(path,regression/f'{index:03d}.xml')
    if totals['tests'] != 316 or any(totals[key] for key in ['failures','errors','skipped']):
        raise ValueError('Current full fresh regression not complete')
    (regression/'summary.json').write_text(json.dumps({**totals,'suites':suites,'freshRun':True},indent=2)+'\n',encoding='utf-8')
    shutil.copyfile(ROOT/'data/local/rehevo-opt-runtime-20261001/identifier-selection-tests-r1.log',
                    regression/'full-test.log')
    old = ROOT/'observability/experiments/rag-evaluation/runs/expanded-dev-20261002-r1'
    for name in ['baseline-chunk-variant.json','actual-chunks.json','chunk-token-estimates.tsv','ingestion.manifest.json']:
        shutil.copyfile(old/name,DEV_RUN/name)
    shutil.copytree(old/'frozen-input',DEV_RUN/'frozen-input')
    print(json.dumps({'strategyFrozen':True,'regression':totals,'strategySha256':verify_strategy()}),flush=True)


def fetch():
    strategy_sha = verify_strategy()
    excluded = [load_gold(ROOT/'data/local/fact-gold-expanded-dev-20261002/manifest.json','agent_verified'),
                load_gold(ROOT/'data/local/fact-gold-heldout-20261002/manifest.json','agent_verified')]
    old_docs = [doc for gold in excluded for doc in gold.documents.values()]
    PACK.mkdir(exist_ok=False)
    documents=[]
    for doc_id,topic,version,url,selector in SOURCES:
        if url in {d['sourceUrl'] for d in old_docs} or topic in {d['topicGroup'] for d in old_docs}:
            raise ValueError('Source/topic overlaps an exposed pack')
        response=requests.get(url,timeout=(15,45))
        response.raise_for_status()
        raw=PACK/(doc_id+'.html');raw.write_bytes(response.content)
        soup=BeautifulSoup(response.content,'html.parser')
        body=soup.select_one(selector)
        if body is None: raise ValueError('Missing official content selector')
        for node in body.select('script,style,nav,footer,aside'):node.decompose()
        text=body.get_text('\n',strip=True).replace('\r\n','\n')+'\n'
        if not 1000 < len(text) < 150000:raise ValueError('Unexpected source size')
        path=PACK/(doc_id+'.txt');path.write_text(text,encoding='utf-8',newline='\n')
        if digest(path) in {d['sourceTextSha256'] for d in old_docs}:raise ValueError('Source bytes overlap')
        documents.append({'documentId':doc_id,'rawPath':raw.name,'canonicalPath':path.name,
                          'documentSha256':digest(raw),'sourceTextSha256':digest(path),
                          'sourceType':'public_primary_document','sourceUrl':url,'resolvedUrl':response.url,
                          'sourceVersion':version,'parserVersion':'bs4-html-main-v1:'+selector,
                          'topicGroup':topic,'split':'test','canonicalCharacters':len(text),
                          'retrievedAt':datetime.now(timezone.utc).isoformat()})
        (PACK/'sources.manifest.json').write_text(json.dumps({'documents':documents,
            'strategyFreezeSha256':strategy_sha,'excludedGoldManifests':[digest(g.manifest_path) for g in excluded],
            'sourceThemeSeparated':True,'independentReviewer':False},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'documentId':doc_id,'characters':len(text)}),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['freeze','fetch'])
    args=parser.parse_args()
    freeze() if args.action=='freeze' else fetch()
