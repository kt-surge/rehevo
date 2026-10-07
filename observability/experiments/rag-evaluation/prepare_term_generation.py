"""Freeze one-factor fixed-context development experiment; preserve current product defaults."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = HERE / 'runs/term-generation-dev-20261005-r2'
OLD = HERE / 'runs/citation-chain-20261005-r1'


def main():
    check = json.loads((HERE / 'runs/term-judge-calibration-20261005-r4/summary-14.json').read_text(encoding='utf-8'))
    if not check['developerCalibrationPass']:
        raise ValueError('Developer calibration gate is not passed')
    if RUN.exists():
        raise ValueError('Preserve existing run')
    old = json.loads((OLD / 'inputs.json').read_text(encoding='utf-8'))
    seal = json.loads((OLD / 'artifacts.sha256.json').read_text(encoding='utf-8'))['files']
    if hashlib.sha256((OLD / 'inputs.json').read_bytes()).hexdigest() != seal['inputs.json']:
        raise ValueError('Public source inputs drifted')
    plan = dict(old, payloadOrigin='frozen-public-term-boundary-dev-v1',
        schedule=[dict(caseId=c['caseId'], arm='current' if c['arm'] == 'plain' else 'grounded')
            for c in old['schedule']],
        factorsChanged=['one terminology sentence in the system prompt'],
        sharedCitationEnabled=True, sharedToolAccess='NONE', contextNumberingUnchanged=True,
        review='Agent development audit, not human or formal new-material evaluation',
        scope='Six exposed cases, 12 paired fixed-context generation calls; no retrieval or latency benefit claim.',
        oldTerminologyRule='使用准确的专业术语，必要时进行解释',
        candidateTerminologyRule='只使用知识库支持的专业术语；知识库未提供释义时不自行补充解释',
        evaluatorCalibrationPath='term-judge-calibration-20261005-r4/summary-14.json',
        evaluatorCalibrationSha256=hashlib.sha256((HERE / 'runs/term-judge-calibration-20261005-r4/summary-14.json').read_bytes()).hexdigest())
    RUN.mkdir()
    (RUN / 'inputs.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
    subprocess.run(['D:/Anaconda/python.exe', str(HERE / 'freeze_citation_sources.py'), str(RUN), 'baseline-source'],check=True)
    extra = RUN / 'driver-source'
    extra.mkdir()
    paths = [HERE / 'RagTermBoundaryStudyMain.java', HERE / 'term-study.init.gradle',
        HERE / 'boot-term-study.ps1', Path(__file__), HERE / 'RAG_TERM_BOUNDARY_DESIGN_2026-10-05.md',
        ROOT / 'gradle/libs.versions.toml',
        ROOT / 'app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java',
        ROOT / 'app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java']
    sources = []
    for index, path in enumerate(paths):
        name = f'{index:02d}-{path.name}'
        shutil.copy2(path,extra/name)
        sources.append(dict(file=name, original=path.relative_to(ROOT).as_posix(),
            sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (extra / 'manifest.json').write_text(json.dumps(dict(sources=sources),indent=2),encoding='utf-8')
    system = (ROOT / 'app/src/main/resources/prompts/knowledgebase-query-system.st').read_text(encoding='utf-8')
    if system.count(plan['oldTerminologyRule']) != 1:
        raise ValueError('Current source differs from frozen candidate assumption')
    (RUN / 'candidate-system-template.st').write_text(system.replace(plan['oldTerminologyRule'],
        plan['candidateTerminologyRule']),encoding='utf-8')
    print(json.dumps(dict(cases=6,generationCalls=12,oneFactor=True,productionDefaultsChanged=False)))


if __name__ == '__main__': main()
