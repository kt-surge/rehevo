"""Author expanded dev facts before seeing retrieval results; same-Agent audit.

Old questions remain regressions. New source/theme-separated final test is not
created or opened here. Exact quotes, constraints and exceptions are preserved.
"""
import json
from datetime import datetime, timezone
from pathlib import Path

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
OLD = ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json"
PACK = ROOT / "data/local/fact-gold-expanded-dev-20261002"
RD, SP = "redis-xautoclaim", "spring-transaction-propagation"
CF, EX = "java21-completable-future", "java21-executor-service"


def main():
    if (PACK / "manifest.json").exists():
        raise ValueError("Gold is frozen; corrections need a new explicit version")
    old = load_gold(OLD, "agent_verified")
    documents = json.loads((PACK / "sources.manifest.json").read_text(encoding="utf-8"))["documents"]
    texts = {doc["documentId"]: (PACK / doc["canonicalPath"]).read_text(encoding="utf-8") for doc in documents}
    facts, cases = list(old.facts.values()), list(old.cases.values())
    reviewed = datetime.now(timezone.utc).isoformat()

    def fact(fid, doc, head, tail):
        text = texts[doc]
        if text.count(head) != 1:
            raise ValueError(f"Missing or ambiguous exact anchor: {fid}")
        start = text.index(head)
        end = text.index(tail, start) + len(tail)
        if end - start > 2500:
            raise ValueError(f"Unexpectedly broad fact: {fid}")
        facts.append({"factId": fid, "documentId": doc, "start": start, "end": end,
                      "exactQuote": text[start:end], "textRole": "documentation_prose",
                      "allowInterChunkWhitespaceGap": True,
                      "mappingPolicyReason": "Pre-retrieval policy: only omitted whitespace between adjacent exact chunk spans may bridge; no non-whitespace or semantic loss is accepted."})

    def case(cid, question, answer, requirements, category="multi_requirement"):
        refs = list(dict.fromkeys(fid for group in requirements for fid in group))
        by_fact = {row["factId"]: row["documentId"] for row in facts}
        cases.append({"id": cid, "split": "dev", "question": question,
                      "questionLanguage": "zh", "sourceLanguage": "en", "category": category,
                      "answerable": True, "sourceDocumentIds": list(dict.fromkeys(by_fact[fid] for fid in refs)),
                      "requirements": [{"id": f"{cid}-r{i+1}", "alternatives": [{"factIds": group}]}
                                       for i, group in enumerate(requirements)],
                      "referenceAnswer": answer, "referenceFactIds": refs,
                      "review": {"status": "agent_verified", "reviewerType": "agent",
                                 "reviewer": "codex-expanded-source-audit-20261002", "reviewedAt": reviewed,
                                 "method": "Same Agent authoring and semantic self-review before retrieval; not independent or human review"},
                      "semanticReviewNotes": "API/命令主体、必要条件、否定与例外均逐项对原文；不推断项目或供应商运行表现。"})

    fact("xa-idle-start", RD, "Like\nXCLAIM\n, the command operates", "start\n.")
    fact("xa-count-scan", RD, "The optional\ncount\nargument,", "less than the specified value.")
    fact("xa-justid", RD, "The optional\nJUSTID\nargument", "retry counter is not incremented.")
    fact("xa-cursor", RD, "The command returns the claimed entries", "eligible for claiming.")
    fact("xa-idle-reset", RD, "Note that only messages that are idle longer", "same message multiple times.")
    fact("xa-deleted-pel", RD, "While iterating the PEL,", "XAUTOCLAIM\ns reply.")
    fact("xa-delivery-count", RD, "Lastly, claiming a message", "not the message itself).")

    fact("tp-required-physical", SP, "PROPAGATION_REQUIRED\nenforces a physical transaction", "service-level transaction).")
    fact("tp-validation", SP, "By default, a participating transaction joins", "read-only outer scope).")
    fact("tp-rollback-marker", SP, "When the propagation setting is", "instead.")
    fact("tp-new-isolation", SP, "PROPAGATION_REQUIRES_NEW\n, in contrast", "outer transaction’s characteristics.")
    fact("tp-pool", SP, "The resources attached to the outer transaction", "concurrent threads by at least 1.")
    fact("tp-nested", SP, "PROPAGATION_NESTED\nuses a single physical transaction", "DataSourceTransactionManager\n.")

    fact("cf-completion-race", CF, "When two or more threads attempt to", "only one of them succeeds.")
    fact("cf-nonasync", CF, "Actions supplied for dependent completions", "a completion method.")
    fact("cf-async-executor", CF, "All\nasync\nmethods without an explicit Executor", "defaultExecutor()\n.")
    fact("cf-exception-wrap", CF, "In case of exceptional completion with a CompletionException,", "in these cases.")
    fact("cf-allof", CF, "allOf\npublic static", "null\n.\nAmong the applications")
    fact("cf-anyof", CF, "anyOf\npublic static", "returns an incomplete CompletableFuture.")
    fact("cf-cancel", CF, "cancel\npublic\nboolean\ncancel", "processing.")
    fact("cf-timeout", CF, "orTimeout\npublic", "this CompletableFuture\nSince:\n9")
    fact("cf-timeout-value", CF, "completeOnTimeout\npublic", "this CompletableFuture\nSince:\n9")

    fact("ex-memory-order", EX, "Memory consistency effects:", "Future.get()\n.")
    fact("ex-shutdown", EX, "shutdown\nvoid\nshutdown", "awaitTermination\nto do that.")
    fact("ex-shutdown-now", EX, "shutdownNow\nList", "list of tasks that never commenced execution")
    fact("ex-await", EX, "awaitTermination\nboolean\nawaitTermination", "whichever happens first.")
    fact("ex-invoke-all", EX, "invokeAll\n<T>\nList\n<\nFuture\n<T>>\ninvokeAll\n(\nCollection\n<? extends\nCallable\n<T>>\xa0tasks)", "each of which has completed")
    fact("ex-invoke-all-timeout", EX, "invokeAll\n<T>\nList\n<\nFuture\n<T>>\ninvokeAll\n(\nCollection\n<? extends\nCallable\n<T>>\xa0tasks,", "some\n         of these tasks will not have completed.")
    fact("ex-invoke-any", EX, "invokeAny\n<T>\nT\ninvokeAny\n(\nCollection\n<? extends\nCallable\n<T>>\xa0tasks)", "tasks that have not completed are cancelled.")
    fact("ex-close", EX, "close\ndefault\nvoid\nclose", "before this method returns.")

    case("expanded-dev-xa-01", "XAUTOCLAIM 的 min-idle-time 用什么单位？扫描起点 start 与被接管消息 ID、idle 有什么关系？",
         "单位是毫秒；接管 idle 严格超过 min-idle-time 且 ID 大于或等于 start 的 pending，归属变为指定 consumer。", [["xa-idle-start"]], "identifier")
    case("expanded-dev-xa-02", "XAUTOCLAIM COUNT 默认是多少？COUNT 设为 25 时最多扫描多少条 pending，是否保证接管 25 条？",
         "默认 100；COUNT=25 最多扫描 250 条，按 idle 过滤后可能接管不足 25 条。", [["xa-count-scan"]], "condition")
    case("expanded-dev-xa-03", "XAUTOCLAIM JUSTID 返回消息正文吗，会增加尝试投递计数吗？不用 JUSTID 呢？",
         "JUSTID 只返回 ID、不返回正文、不增加重试计数；通常的 XAUTOCLAIM 接管会增加尝试投递计数。", [["xa-justid"], ["xa-delivery-count"]], "identifier")
    case("expanded-dev-xa-04", "XAUTOCLAIM 返回 0-0 就意味着以后不用再扫了吗？接管消息对其 idle 时间有什么影响？",
         "0-0 只表示本次 PEL 扫描结束，之后可能有旧 pending 达到 idle 条件，可再从 0-0 扫描；接管会重置消息 idle。", [["xa-cursor"], ["xa-idle-reset"]])
    case("expanded-dev-xa-05", "Redis 7.0 起 XAUTOCLAIM 遇到正文已被 XDEL 或裁剪删除的 pending 时，是接管并恢复正文，还是怎样处理？",
         "不接管、不恢复正文；从 PEL 删除引用，并在返回值中列出删除的 ID。", [["xa-deleted-pel"]], "condition")

    case("expanded-dev-tp-01", "PROPAGATION_REQUIRED 内层声明不同隔离级别或 read-only，默认会覆盖外层吗？validateExistingTransaction=true 有何影响？",
         "默认加入外层并忽略局部隔离、超时、只读；开启校验后拒绝隔离不匹配，也拒绝只读外层加入读写内层。", [["tp-validation"]], "identifier")
    case("expanded-dev-tp-02", "Spring REQUIRED 内层标记 rollback-only 后，外层仍调用 commit，为什么会收到 UnexpectedRollbackException？",
         "各逻辑作用域共享同一物理事务，内层 rollback-only 影响外层提交；异常表明实际回滚，避免调用者误以为提交成功。", [["tp-rollback-marker"]], "condition")
    case("expanded-dev-tp-03", "REQUIRES_NEW 的物理事务、锁释放和隔离设置与外层有什么关系？连接池是否只要等于并发线程数就够？",
         "独立物理事务，可独立提交/回滚，内层完成即释放其锁，可独立隔离/超时/只读；外层仍占资源，池耗尽可能死锁，官方要求适当容量至少比并发线程数多 1。", [["tp-new-isolation"], ["tp-pool"]])
    case("expanded-dev-tp-04", "PROPAGATION_NESTED 是否像 REQUIRES_NEW 一样开启独立物理事务？内层部分回滚后外层可以继续吗，依赖什么资源能力？",
         "NESTED 使用同一物理事务和多个保存点，部分回滚后外层可以继续；通常映射 JDBC 保存点，只适用于 JDBC 资源事务。", [["tp-nested"], ["tp-new-isolation"]])

    case("expanded-dev-cf-01", "CompletableFuture 的 non-async 回调必定跑在线程池吗？未显式提供 Executor 的 async 方法使用哪个执行器，有什么并行度例外？",
         "non-async 可在完成当前 Future 的线程或其他完成方法调用者执行；async 默认 commonPool，若不支持至少 2 的并行度，则每任务新建线程；子类非静态方法可覆盖 defaultExecutor。", [["cf-nonasync"], ["cf-async-executor"]])
    case("expanded-dev-cf-02", "CompletableFuture.allOf 是否返回每个任务的结果列表？某个输入异常和输入数组为空时分别怎样完成？",
         "不返回结果列表，要逐个查看输入；任一输入异常使返回 Future 以 CompletionException 包装异常完成；空输入返回已完成、值为 null 的 Future。", [["cf-allof"]], "identifier")
    case("expanded-dev-cf-03", "CompletableFuture.anyOf 是等待第一个成功还是第一个完成？第一个完成是异常与传入空数组时怎样处理？",
         "等待任一完成，正常用其结果、异常用 CompletionException 包装；空输入得到未完成的 Future。", [["cf-anyof"]], "condition")
    case("expanded-dev-cf-04", "CompletableFuture.cancel(true) 会用中断保证停止后台计算吗？未完成的 dependent Future 会如何完成？",
         "不会；mayInterruptIfRunning 在该实现无效，取消是 CancellationException 异常完成；未完成依赖以该异常为原因的 CompletionException 完成。", [["cf-cancel"]], "identifier")
    case("expanded-dev-cf-05", "CompletableFuture 的 orTimeout 和 completeOnTimeout 在超时且尚未完成时有何区别，返回新 Future 还是自身？",
         "orTimeout 以 TimeoutException 异常完成，completeOnTimeout 用给定值正常完成；两者都返回自身。", [["cf-timeout"], ["cf-timeout-value"]], "identifier")
    case("expanded-dev-cf-06", "CompletableFuture 已以 CompletionException 异常完成时，get 与 join 在异常包装上有什么区别？多个线程竞争 complete、cancel 能都成功吗？",
         "get 以相同 cause 抛 ExecutionException，join 直接抛 CompletionException；多个完成/异常完成/取消操作只有一个成功。", [["cf-exception-wrap"], ["cf-completion-race"]])

    case("expanded-dev-ex-01", "ExecutorService.shutdown 与 shutdownNow 都会等待运行中任务终止吗？后者是否保证所有任务被停止，返回什么？",
         "两者都不等待，应使用 awaitTermination；shutdown 接受的旧任务继续执行，拒绝新任务；shutdownNow 仅尽力停止、不保证不响应中断的任务终止，返回未开始的任务列表。", [["ex-shutdown"], ["ex-shutdown-now"]])
    case("expanded-dev-ex-02", "ExecutorService.invokeAll 带 timeout 时，返回 Future 的顺序按完成顺序吗，超时后未完成任务怎样处理？",
         "按输入集合迭代顺序，非完成先后顺序；全部完成或超时即返回，未完成任务被取消，完成也可能是异常完成。", [["ex-invoke-all-timeout"]], "identifier")
    case("expanded-dev-ex-03", "Java 21 ExecutorService.close 与 shutdown 在等待行为上有什么差别？close 等待中被中断后怎样处置并恢复中断状态？",
         "close 有序关闭并等待终止，shutdown 不等待；close 被中断后如 shutdownNow 停止执行且不执行等待任务，继续等待活跃任务完成，返回前重新设置中断状态。", [["ex-close"], ["ex-shutdown"]], "condition")
    case("expanded-dev-ex-04", "向 ExecutorService 提交任务前的动作、任务中的动作和 Future.get 取回结果之间，文档规定了怎样的可见性顺序？",
         "提交前动作 happen-before 任务动作，任务动作又 happen-before 通过 Future.get 取回结果。", [["ex-memory-order"]], "paraphrase")

    case("expanded-dev-cross-01", "分别解释 CompletableFuture.anyOf 与 ExecutorService.invokeAny：哪一个要求成功结果，另一个遇到异常完成会怎样？",
         "invokeAny 返回一个成功任务的结果，返回时取消未完成任务；anyOf 接受任一完成，异常完成会以 CompletionException 包装。", [["cf-anyof"], ["ex-invoke-any"]], "cross_document")
    case("expanded-dev-cross-02", "需要等待全部异步工作时，CompletableFuture.allOf 和 ExecutorService.invokeAll 的返回结果形式各是什么？",
         "allOf 返回 Void Future，具体结果需看各输入；invokeAll 返回按输入迭代顺序排列的各任务 Future。", [["cf-allof"], ["ex-invoke-all"]], "cross_document")
    case("expanded-dev-cross-03", "取消异步任务时请分别说明：CompletableFuture.cancel(true) 的中断标志是否生效；ExecutorService.shutdownNow 是否保证任务终止。",
         "cancel 的中断标志无效，只做异常完成；shutdownNow 尽力停止且不等待，不响应中断的任务可能一直不终止。", [["cf-cancel"], ["ex-shutdown-now"]], "cross_document")
    case("expanded-dev-cross-04", "后台工作流中，XAUTOCLAIM 接管会如何改变消息 idle；REQUIRES_NEW 的内层回滚是否影响外层的回滚状态？",
         "接管重置 idle；REQUIRES_NEW 物理事务独立，内层回滚状态不影响外层。", [["xa-idle-reset"], ["tp-new-isolation"]], "cross_document")
    case("expanded-dev-cross-05", "请分别说明 XREADGROUP 中 > 与历史 ID 0 的读取范围，以及 XAUTOCLAIM COUNT 的默认值和扫描数量上限。",
         "普通 > 读取未交给消费者的新消息，0 读自身 pending 历史；XAUTOCLAIM COUNT 默认 100、最多扫描 COUNT×10 条且实际接管可能少于 COUNT。", [["rd-new-messages"], ["rd-history-ignored"], ["xa-count-scan"]], "cross_document")
    case("expanded-dev-cross-06", "Spring 默认 proxy 模式内部调用 @Transactional 会触发事务吗？另一个问题：CompletableFuture 异步方法未指定 Executor 时默认在哪里执行？",
         "同对象内部调用不经代理，不触发对应注解事务；async 默认 commonPool，不能提供至少 2 并行度时每任务新建线程，子类非静态方法可改 defaultExecutor。", [["sp-proxy-self-call"], ["cf-async-executor"]], "cross_document")
    case("expanded-dev-cross-07", "分别核对三个配置边界：pg_trgm 的 siglen 默认与单位；XAUTOCLAIM min-idle-time 的单位；Spring REQUIRED 默认是否接受内层不同隔离级别。",
         "siglen 默认 12 字节；idle 单位毫秒且接管条件严格超过阈值；REQUIRED 默认加入外层并忽略内层隔离，严格校验需要 validateExistingTransaction。", [["tg-signature"], ["xa-idle-start"], ["tp-validation"]], "cross_document")
    case("expanded-dev-cross-08", "PostgreSQL Repeatable Read 遇到并发更新序列化错误应怎样重试？同时说明 ExecutorService.close 是否等待任务结束。",
         "从头重试整个事务；close 有序关闭并等待任务完成及 executor 终止，中断后的细节也不等于立即返回。", [["pg-rr-retry"], ["ex-close"]], "cross_document")

    for i, (question, reason, doc) in enumerate([
        ("Rehevo 生产 XAUTOCLAIM 的 COUNT 和 min-idle-time 实际值是多少？", "官方命令说明不包含项目运行配置。", RD),
        ("Rehevo 使用 REQUIRES_NEW 后，真实连接池等待 P95 降低了多少？", "没有指定项目的配对性能测量。", SP),
        ("Java 官方文档能证明 Rehevo 的 cancel 已成功取消供应商远端 TTS 计算吗？", "本地 Future 语义不是供应商远端取消证据。", CF),
        ("Rehevo 当前线上线程池究竟有几条核心线程，过去一周拒绝率是多少？", "通用接口文档没有项目有效配置或线上观测分母。", EX),
    ], 1):
        cid = f"expanded-dev-unavailable-{i:02d}"
        case(cid, question, "这些资料不足以确定该项目实际值或测量结果。", [], "unanswerable")
        cases[-1].update(answerable=False, sourceDocumentIds=[doc], unanswerableReason=reason,
                         searchedSourceDocumentIds=[row["documentId"] for row in documents])
    for row in cases[:len(old.cases)]:
        if not row["answerable"]:
            row = row.copy()
            row["searchedSourceDocumentIds"] = [doc["documentId"] for doc in documents]
            # References to the old four remain true: none of the new official
            # sources contain Rehevo runtime settings or production outcomes.
            cases[cases.index(next(item for item in cases if item["id"] == row["id"]))] = row
    for name, rows in (("facts.jsonl", facts), ("dev.jsonl", cases)):
        (PACK / name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    manifest = {"schemaVersion": "rehevo-fact-gold-v1", "packId": "expanded-official-dev-20261002-r1",
                "documents": documents, "factsPath": "facts.jsonl", "factsSha256": digest(PACK / "facts.jsonl"),
                "splits": {"dev": {"path": "dev.jsonl", "sha256": digest(PACK / "dev.jsonl"),
                                     "cases": len(cases), "answerable": sum(row["answerable"] for row in cases)}},
                "frozenAt": reviewed, "humanReviewed": False, "testMaterialsPrepared": False,
                "freezeScope": "expanded-development-only-before-retrieval",
                "previousGoldSha256": digest(OLD), "oldRegressionCases": len(old.cases),
                "limitations": ["Same Agent authoring and source self-review, not independent or human review",
                                "Chinese questions with English official sources, not production user corpus",
                                "Old dev regressions reused; no final test or generation score",
                                "Redis latest reference is date frozen, not the deployed Redis 7 feature guarantee"]}
    (PACK / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    gold = load_gold(PACK / "manifest.json", "agent_verified")
    print(json.dumps({"documents": len(gold.documents), "facts": len(gold.facts), "cases": len(gold.cases),
                      "answerable": sum(row["answerable"] for row in cases), "sha256": digest(PACK / "manifest.json")}), flush=True)


if __name__ == "__main__":
    main()
