"""Freeze one references-only source variant and shared public diagnostic inputs."""
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
arm = sys.argv[1]
assert arm in ('baseline', 'candidate')
run = BASE / f'reference-facts-source-{arm}-20261006-r1'
run.mkdir()
(run / 'sources').mkdir()
(run / 'references').mkdir()
paths = list((ROOT / 'app/src/main/java/interview/guide/common/ai').glob('*.java'))
paths += list((ROOT / 'app/src/main/java/interview/guide/common/evaluation').glob('*.java'))
paths += list((ROOT / 'app/src/main/java/interview/guide/modules/interview/service').glob('*.java'))
paths += [ROOT / name for name in (
    'app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java',
    'app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillProperties.java',
    'app/src/main/java/interview/guide/modules/interview/model/InterviewQuestionDTO.java',
    'app/src/main/java/interview/guide/modules/interview/model/InterviewPlan.java',
    'app/src/main/java/interview/guide/modules/interview/model/InterviewReportDTO.java',
    'app/src/main/resources/application.yml',
    'app/src/main/resources/skills/java-backend/SKILL.md',
    'app/src/main/resources/skills/java-backend/skill.meta.yml',
    'observability/experiments/interview-adaptation/ReferenceFactsStudyMain.java',
    'observability/experiments/interview-adaptation/reference-facts.init.gradle',
    'observability/experiments/interview-adaptation/REFERENCE_FACTS_DESIGN_2026-10-06.md',
    'observability/experiments/interview-adaptation/prepare_reference_facts.py')]
paths += list((ROOT / 'app/src/main/resources/prompts').glob('interview-*.st'))
paths += [ROOT / 'app/src/main/resources/skills/_shared/references' / name
          for name in ('spring.md', 'mysql.md', 'mq.md')]
manifest = []
for index, path in enumerate(sorted(set(paths))):
    raw = Path('\\\\?\\' + str(path.resolve()) if os.name == 'nt' else path).read_bytes()
    frozen = f'sources/{index:03d}-{path.name}'
    (run / frozen).write_bytes(raw)
    if path.name in ('spring.md', 'mysql.md', 'mq.md'):
        (run / 'references' / path.name).write_bytes(raw)
    manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen=frozen,
                         sha256=hashlib.sha256(raw).hexdigest()))
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
for args, file in ((['git', 'rev-parse', 'HEAD'], 'git-head.txt'),
                   (['git', 'diff', '--name-only'], 'git-dirty-names.txt')):
    value = subprocess.run(args, cwd=ROOT, check=True, capture_output=True).stdout
    (run / file).write_bytes(value)
if arm == 'baseline':
    items = [
        ('boot-config', 'SPRING', 'Spring', 'spring.md', '配置来源覆盖顺序',
         '分析默认启动方式下同时设置 application.yml 中 server.port=8080、环境变量 SERVER_PORT=8081 和命令行 --server.port=8082 的结果；没有自定义 Environment 或测试属性覆盖。',
         '说明最终值及覆盖顺序，并将同目录 properties/yaml 规则与跨来源覆盖分开。'),
        ('spring-proxy', 'SPRING', 'Spring', 'spring.md', '类代理事务方法可见性',
         '解释 Spring Framework 6+ 默认事务类代理，通过该代理外部调用带 @Transactional 的 protected 方法；与 JDK 接口代理和同类内部调用对比，没有配置 publicMethodsOnly。',
         '能指出 protected/package-visible 的类代理支持条件，不把所有非 public 方法判为必然失效。'),
        ('mysql-order', 'MYSQL', 'MySQL', 'mysql.md', '等值前缀与索引排序',
         "分析 MySQL 8.4 InnoDB 中 tenant_id=12 AND state='READY' ORDER BY created_at DESC 的查询，候选索引 (tenant_id,state,created_at) 和 (state,tenant_id,created_at)；不要假设实际执行计划或数据分布。",
         '解释两种等值前缀均可能支持后续排序，区分索引能力与优化器是否实际选用。'),
        ('mq-commit', 'MQ', '消息队列', 'mq.md', '业务提交与消息确认边界',
         '分析消息消费先成功提交业务数据库、随后确认消息，但在确认前进程崩溃的情况；区分 RabbitMQ ACK 与 Kafka offset，不能把 Kafka/RocketMQ 事务自动推广到外部数据库恰好一次。',
         '说明重复投递与原子幂等边界，以及先确认后处理可能丢失；不声称不同中间件完全相同。')]
    (run / 'inputs').mkdir()
    for case, key, label, reference, competency, action, criteria in items:
        value = dict(origin='public-reference-boundary-diagnostic-20261006-v1', caseId=case,
                     categoryKey=key, label=label, reference=reference,
                     trainingTask=dict(competency=competency, questionIndexes=[0],
                         reason='公开受控练习定义，不是候选人已验证能力缺口', action=action,
                         completionCriteria=criteria, priority=3))
        (run / 'inputs' / f'{case}.json').write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
else:
    old = BASE / 'reference-facts-source-baseline-20261006-r1'
    prior = json.loads((old / 'source-manifest.json').read_text(encoding='utf-8'))['files']
    before = {item['path']: item['sha256'] for item in prior}
    changed = [item['path'] for item in manifest if before[item['path']] != item['sha256']]
    expected = [f'app/src/main/resources/skills/_shared/references/{name}'
                for name in ('spring.md', 'mysql.md', 'mq.md')]
    assert sorted(changed) == sorted(expected), changed
    (run / 'comparison-changes.json').write_text(json.dumps(dict(changed=changed,
        promptsCodeSchemaUnchanged=True, crossModelComparisons=0), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, arm=arm, sourceFiles=len(manifest), externalModelCalls=0)))
