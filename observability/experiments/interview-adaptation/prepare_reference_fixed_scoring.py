"""Freeze two public fixed answers, with expected labels kept out of model input."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
run = ROOT / 'observability/experiments/voice-frame-pipeline/runs/reference-fixed-scoring-inputs-20261006-r1'
run.mkdir()
question = ('Spring Framework 6+，默认事务类代理且未配置 publicMethodsOnly。'
    '同包另一个 Bean 经代理外部调用 protected 的 @Transactional 方法能否被事务拦截？'
    '与 JDK 接口代理和同类自调用比较；自调用发生在已有事务方法内部时，业务是否必然无事务？')
answers = [
    '这个限定条件下 protected 方法可由类代理事务拦截；接口代理要求公开的接口方法。'
    '关键是外部调用经过代理。自调用绕过代理，不触发被调用方法的新事务属性，'
    '但若外层已有事务，内部业务仍可参与外层事务；不能笼统说整个业务无事务。'
    '配置 publicMethodsOnly 后要另外判断可见性支持。',
    '只有 public 方法才能有事务，所以 protected 在 Spring 6 的类代理中也一定不生效。'
    '同类调用的内部方法即使外层已经有事务，也一定完全没有事务；'
    '只要设置 proxyTargetClass=true 就能让所有自调用自动触发新的事务。']
guide = dict(competency='事务代理、方法可见性与自调用边界',
    keyPoints=['方法可见性与代理类型', '版本和配置条件', '代理外部调用、自调用与外层事务区别'],
    followUpDirection='说明条件及验证方法', source='public-fixed-answer-reference-diagnostic',
    rubric=[dict(level=i, criteria=text) for i,text in enumerate([
        '核心结论错误或答非所问', '只有零散概念且有明显错误',
        '核心基本正确但缺少边界', '结论正确完整，能解释原理和边界',
        '能分析失败模式并给出可验证证据'])], trainingTargetIds=[], trainingTargetsDeclared=False)
payload = dict(payloadOrigin='public-fixed-answer-reference-diagnostic-20261006-v1',
    skillId='java-backend', qaRecords=[dict(questionIndex=i, question=question,
        category='SPRING', userAnswer=answer, evaluationGuide=guide) for i,answer in enumerate(answers)])
raw = json.dumps(payload, ensure_ascii=False, indent=2).encode()
(run / 'inputs.json').write_bytes(raw)
(run / 'expected-labels-not-for-model.json').write_text(json.dumps(dict(
    source='Agent development label checked against official transaction documentation',
    exposedDevelopmentDiagnostic=True, independentHumanGold=False,
    expected=[dict(questionIndex=0, minimumRubricLevel=3, label='correct-boundaries'),
              dict(questionIndex=1, maximumRubricLevel=1, label='false-core-claims')],
    inputSha256=hashlib.sha256(raw).hexdigest()), indent=2), encoding='utf-8')
for name in ('REFERENCE_FIXED_SCORING_PLAN_2026-10-06.md', 'prepare_reference_fixed_scoring.py'):
    (run / name).write_bytes((Path(__file__).parent / name).read_bytes())
(run / 'prepare-status.json').write_text(json.dumps(dict(prepared=True,
    externalModelCalls=0, actualEvaluationResults=False, labelsExcludedFromInputs=True,
    unrunDevelopmentPreflight=True, fullGoalStatus='active'), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, inputSha256=hashlib.sha256(raw).hexdigest(), actualModelCalls=0)))
