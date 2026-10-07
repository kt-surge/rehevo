"""Prepare controlled, exposed development fixtures before any new external request."""
import hashlib
import json
from pathlib import Path
import random
import sys


ROOT = Path(__file__).resolve().parents[3]
SEED = 2026100508


def main(output):
    prior = ROOT / 'observability/experiments/voice-frame-pipeline/runs/20261005-r7-final'
    events = [json.loads(line) for line in
              (prior / 'development-regression/events.jsonl').read_text(encoding='utf-8').splitlines()]
    terminal = {row['label']: row['text'] for row in events
                if row['kind'] == 'terminal' and row.get('sessionId') == 17}
    # Replay the fixed r7 history up to the offending answer, not that answer itself.
    replay = [
        '候选人：浏览器播放 PCM 时给每个轮次分配标识，用户停止后同时清空未播音频和取消服务端生成。',
        '面试官：' + terminal['voice-cancel-1'],
        '候选人：网络断开时即使 TTS 的旧回调晚到，也会检查轮次标识，关闭流资源，避免旧音频写出。',
        '面试官：' + terminal['voice-cancel-2'],
    ]
    cases = [
        dict(id='r7-unconfirmed-memory', history=replay,
             input='只知道我会重试，具体的资源回收和超时边界还没想清楚。',
             source='r7-final exposed failed third-turn context, opener intentionally omitted identically',
             criteria='不能将面试官的内存/Redis选项当成已确认实现；明确假设后问资源/取消边界可接受'),
        dict(id='confirmed-redis', history=replay + [
            '候选人：我确认轮次标识存 Redis，不是进程内存；只是取消后的资源回收还没实现。',
            '面试官：如果 Redis 暂时不可用，你会直接继续写出旧音频吗？'],
             input='不会继续写，我要先保证取消后旧音频不会送到浏览器。',
             criteria='保留明确的Redis事实；不能声称仅存进程内存；聚焦停止写出/资源释放'),
        dict(id='unconfirmed-replica', history=[
            '候选人：我在 PostgreSQL 写入订单后提交事务，再返回成功；我没有讲查询路由。',
            '面试官：后续查询是主库还是只读副本？'],
             input='后续读取路由和一致性验证我还没做，现在只确认提交事务后才返回。',
             criteria='不能声称当前已读副本；明确如果读副本的假设可接受；聚焦提交/可见性验证'),
        dict(id='confirmed-primary', history=[
            '候选人：写入后立即查询明确走主库，不经过只读副本。',
            '面试官：如果采用副本读取，复制延迟可能导致什么问题？'],
             input='我目前没有启用副本读取，需要先验证主库提交后查询能看到同一条记录。',
             criteria='不能把面试官的副本假设当成当前实现；聚焦主库提交后可见性验证'),
        dict(id='unconfirmed-kafka', history=[
            '候选人：异步向量化任务使用执行版本，旧 worker 完成时需检查当前版本。',
            '面试官：消息队列用的是 Kafka 还是 Redis Stream？'],
             input='消息队列具体选型还没确定。我现在只设计了租约过期后新 worker 接管，旧 worker 不能覆盖结果。',
             criteria='不能声称已使用Kafka或Redis Stream；可问原子检查/旧结果丢弃/ACK边界'),
        dict(id='unconfirmed-resume', history=[
            '候选人：我把RAG检索和生成分开评估，知识库是公开API技术文档。',
            '面试官：你的知识库是否也收录了简历和项目资料？'],
             input='没有简历，只有公开API技术文档；目录命中但实现正文缺失时，回答可能漏掉关键前置条件。',
             criteria='不能声称已有简历资料；聚焦必要正文证据、前置条件/回答证据验证'),
        dict(id='no-history-voice', history=[],
             input='语音流水线在服务器收到LLM首字后就启动TTS，但浏览器真正听到语音还要等缓冲。我想量化优化响应速度。',
             criteria='两组payload完全相同，波动控制；可问用户结束说话到浏览器可听首帧的口径，不能混同首字/首音'),
        dict(id='no-history-rag', history=[],
             input='我的公开API知识库有时只召回方法目录，没召回实现正文。我把必要证据覆盖、回答忠实度、条件完整性和引用支持分开评估。',
             criteria='两组payload完全相同，波动控制；聚焦检索正文或回答证据验证，不能虚构简历/接口调试事实'),
    ]
    rng = random.Random(SEED)
    order = list(range(len(cases)))
    rng.shuffle(order)
    # Four AB and four BA orders, randomized before calls.
    first = ['flat'] * 4 + ['roles'] * 4
    rng.shuffle(first)
    schedule = []
    for i, arm in zip(order, first):
        schedule.extend([dict(caseId=cases[i]['id'], arm=arm),
                         dict(caseId=cases[i]['id'], arm='roles' if arm == 'flat' else 'flat')])
    data = dict(scope='public fixed-history development preflight; self-review, not independent blind test',
                seed=SEED, cases=cases, schedule=schedule,
                priorEventsSha256=hashlib.sha256((prior / 'development-regression/events.jsonl').read_bytes()).hexdigest())
    if output.exists():
        raise ValueError('Inputs already exist; do not rewrite a frozen study')
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(cases=len(cases), calls=len(schedule), seed=SEED,
                         sha256=hashlib.sha256(output.read_bytes()).hexdigest())))


if __name__ == '__main__':
    main(Path(sys.argv[1]))
