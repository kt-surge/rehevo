"""Add provenance, source-author body audit and complete local usage ledger before sealing."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

from experimental_index_snapshot import current, assert_scope, vectors_hash, query

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUNS = HERE / 'runs'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, value):
    if path.exists():
        raise ValueError('Preserve existing evidence: ' + path.name)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def main():
    calibration = [RUNS / f'term-judge-calibration-20261005-r{i}' for i in range(1,5)]
    gen = RUNS / 'term-generation-dev-20261005-r2'
    failed = RUNS / 'term-generation-dev-20261005-r1'
    actual = RUNS / 'term-actual-context-20261005-r1'
    if any((p/'artifacts.sha256.json').exists() for p in calibration+[gen,failed,actual]):
        raise ValueError('Do not write to sealed runs')
    v = current(); assert_scope(v)
    counts = query("SELECT json_build_object('documents',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store),'ragSessions',(SELECT count(*) FROM rag_chat_sessions),'ragMessages',(SELECT count(*) FROM rag_chat_messages),'voiceSessions',(SELECT count(*) FROM voice_interview_sessions));")
    expected = '76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b'
    if vectors_hash(v) != expected or counts != dict(documents=15,vectors=123,ragSessions=0,ragMessages=0,voiceSessions=0):
        raise ValueError('Scope changed')
    scope = dict(publicVectorSha256=expected,counts=counts,publicVectors=24,noSessionWrites=True)
    write(gen/'scope-after.json',scope)
    write(failed/'prepare-failure.json',dict(stage='source freeze before candidate/model execution',
        actualChatCalls=0,partialBaselineFiles=76,partialDriverFiles=7,
        failedExpectedPath='app/src/main/java/interview/guide/common/constant/PromptSecurityConstants.java',
        verifiedActualPath='app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java',
        note='Post-run diagnostic of the observed FileNotFoundError, not an original stdout capture. New r2 directory used; partial r1 preserved.'))
    write(gen/'local-audit-initial-failure.json',dict(actualExternalCalls=0,exception='KeyError: original',
        stage='audit_term_generation.py line 43',
        cause='baseline manifest uses file; driver manifest uses file plus original',
        resolution='Accept original when present, otherwise file; rerun checks all 84 unchanged sources.',
        note='Diagnostic transcription of actual local tool result; raw tool trace is not claimed to be stored.'))
    reviews=[]
    patterns = dict(pelDefinition=r'Pending\s+Entries\s+List\s*\(PEL\)',
        noackCondition=r'cases where reliability is not a requirement and the occasional message loss\s+is acceptable',
        claimException=r'When used together with\s+CLAIM\s*,\s*NOACK\s+does not apply for retrieved pending entries')
    for index in range(2):
        bodies = read(actual/f'{index:02d}-selected-public-bodies.json')
        findings={}
        for name,pattern in patterns.items():
            spans=[]
            for body in bodies:
                for match in re.finditer(pattern,body['content']):
                    spans.append(dict(vectorDocumentId=body['id'],bodySha256=body['bodySha256'],
                        start=match.start(),end=match.end(),exactQuote=match.group()))
            if not spans:
                raise ValueError('Expected factual source span missing: '+name)
            findings[name]=spans
        reviews.append(dict(requestIndex=index,findings=findings,allThreeFactsInSelectedBody=True))
    write(actual/'source-body-audit.json',dict(reviewerType='source-author Agent',humanReviewed=False,
        reviews=reviews,initialLiteralCheckFalseWasFactMissing=False,
        initialLiteralMismatch='apply to versus actual apply for',
        originalSummaryPreserved=True,noChatGeneration=True,
        conclusion='Actual selected public bodies contain definition, applicability condition and exception; excerpt-only unprovided definitions do not prove a production retrieval deficit.'))
    token_pattern=r'^gen_ai_client_token_usage_total\{[^\n]*gen_ai_operation_name="embedding"[^\n]*gen_ai_token_type="total"[^\n]*\}\s+([0-9.]+)$'
    before=sum(float(x) for x in re.findall(token_pattern,(actual/'metrics-before.txt').read_text(),re.M))
    after=sum(float(x) for x in re.findall(token_pattern,(actual/'01-metrics-after.txt').read_text(),re.M))
    if after-before !=116:
        raise ValueError('Unexpected actual Embedding usage')
    summaries=[read(p/f'summary-{n:02d}.json') for p,n in zip(calibration,[2,9,7,14])]
    generation=read(gen/'final-audit.json')
    ledger=dict(scope='This term calibration/generation/context phase only, not total goal usage.',
        calibrationCalls=sum(x['actualExternalCalls'] for x in summaries),
        calibrationKnownTokens=sum(x['knownTokenTotal'] for x in summaries),
        generationCalls=generation['actualGenerationCalls'],generationKnownTokens=generation['totalKnownTokens'],
        totalChatRequests=sum(x['actualExternalCalls'] for x in summaries)+generation['actualGenerationCalls'],
        totalKnownChatTokens=sum(x['knownTokenTotal'] for x in summaries)+generation['totalKnownTokens'],
        unknownChatUsageRequests=sum(x['unknownUsageRequests'] for x in summaries)+generation['unknownUsageRequests'],
        retrievalRequests=2,vectorSearchCalls=2,embeddingKnownTokens=int(after-before),
        usageSource='Per-provider final response usage for Chat; isolated App fresh total-token counter delta for Embedding. Input/output/total not summed together.',
        previousEightApplicationComparisonAuthorizationReused=False)
    write(actual/'usage-ledger.json',ledger)
    shutil.copy2(actual/'usage-ledger.json',gen/'phase-usage-ledger.json')
    write(actual/'scope-final.json',scope)
    # Preserve the helper/source versions used to obtain/reconstruct the actual context.
    subprocess.run(['D:/Anaconda/python.exe',str(HERE/'freeze_citation_sources.py'),str(actual),'baseline-source'],check=True)
    extra=actual/'audit-source';extra.mkdir()
    paths=[Path(__file__),HERE/'audit_term_retrieved_context.py',HERE/'experimental_index_snapshot.py',
        HERE/'ingest_primary_dev.py',HERE/'fact_gold.py',HERE/'audit_term_generation.py']
    sources=[]
    for index,path in enumerate(paths):
        name=f'{index:02d}-{path.name}';shutil.copy2(path,extra/name)
        sources.append(dict(file=name,original=path.relative_to(ROOT).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    write(extra/'manifest.json',dict(sources=sources))
    for p in calibration:
        shutil.copy2(HERE/'RAG_TERM_JUDGE_CALIBRATION_RESULTS_2026-10-05.md',p/'final-phase-report.md')
    for p in [gen,failed,actual]:
        shutil.copy2(HERE/'RAG_TERM_BOUNDARY_RESULTS_2026-10-05.md',p/'final-phase-report.md')
    shutil.copy2(HERE/'audit_term_generation.py',gen/'audit_term_generation.py')
    # One additional input semantic note; original r2 results and expectations remain intact.
    write(calibration[1]/'source-url-contamination-audit.json',dict(caseId='version-absent',
        sourceUrlContainedVersion=True,versionInSourceUrl='/17/',
        originalMismatchPreserved=True,scoreAsCleanMissingVersionControl=False,
        note='The r2 evidence payload included a versioned sourceUrl; r3/r4 supplied only body. This fixture cannot establish a judge error on version provenance.'))
    print(json.dumps(ledger))


if __name__ == '__main__':
    main()
