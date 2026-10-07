"""Freeze new test facts/questions after policy freeze and before any test retrieval.

Same-Agent authoring is explicitly declared. No test outcome is consumed here.
"""
import json
from datetime import datetime, timezone
from pathlib import Path

from fact_gold import digest, load_gold
from freeze_heldout_sources import ROOT, PACK, verify_strategy

Q, C, H, F = "python313-queue", "python313-contextvars", "java21-http-client", "java21-files"


def main():
    strategy_sha = verify_strategy()
    if (PACK / "manifest.json").exists():
        raise ValueError("Test Gold frozen; do not alter it after retrieval")
    sources = json.loads((PACK / "sources.manifest.json").read_text(encoding="utf-8"))
    documents = sources["documents"]
    texts = {d["documentId"]: (PACK / d["canonicalPath"]).read_text(encoding="utf-8") for d in documents}
    facts, cases = [], []
    reviewed = datetime.now(timezone.utc).isoformat()

    def fact(fid, doc, head, tail):
        text = texts[doc]
        if text.count(head) != 1:
            raise ValueError(f"Missing/ambiguous test source anchor: {fid}")
        start = text.index(head)
        end = text.index(tail, start) + len(tail)
        if end - start > 2500:
            raise ValueError(f"Overly broad test fact: {fid}")
        facts.append({"factId": fid, "documentId": doc, "start": start, "end": end,
                      "exactQuote": text[start:end], "textRole": "documentation_prose",
                      "allowInterChunkWhitespaceGap": True,
                      "mappingPolicyReason": "Frozen before test retrieval: only whitespace omitted between adjacent exact source spans can bridge; code examples and semantic loss are excluded."})

    def case(cid, question, answer, groups, category="multi_requirement"):
        refs = list(dict.fromkeys(fid for group in groups for fid in group))
        lookup = {row["factId"]: row["documentId"] for row in facts}
        cases.append({"id": cid, "split": "test", "question": question, "questionLanguage": "zh",
                      "sourceLanguage": "en", "category": category, "answerable": True,
                      "sourceDocumentIds": list(dict.fromkeys(lookup[fid] for fid in refs)),
                      "requirements": [{"id": f"{cid}-r{i+1}", "alternatives": [{"factIds": group}]}
                                       for i, group in enumerate(groups)],
                      "referenceFactIds": refs, "referenceAnswer": answer,
                      "review": {"status": "agent_verified", "reviewerType": "agent",
                                 "reviewer": "codex-new-source-test-audit-20261002", "reviewedAt": reviewed,
                                 "method": "Same Agent source self-review after frozen strategy, before first test run; not independent/human review"},
                      "semanticReviewNotes": "每项条件和例外对照本包原文；原问题始终保留，不含任何项目运行测量。"})

    fact("pq-size", Q, "Return the approximate size of the queue.  Note, qsize() > 0 doesn’t\nguarantee that a subsequent get() will not block, nor", "guarantee that put() will not block.")
    fact("pq-put", Q, "\nQueue.\nput\n(", "ignored in that case).")
    fact("pq-task-done", Q, "Indicate that a formerly enqueued task is complete.", "the queue.")
    fact("pq-join", Q, "Blocks until all items in the queue have been gotten and processed.", "join()\nunblocks.")
    fact("pq-shutdown-normal", Q, "Queue.\nshutdown\n(", "Once the queue is empty, future calls to\nget()\nwill\nraise\nShutDown\n.")
    fact("pq-shutdown-immediate", Q, "If\nimmediate\nis true,", "invariant for joining a queue.")
    fact("pq-simple-put", Q, "SimpleQueue.\nput\n(", "Queue.put()\n.")

    fact("pc-module-level", C, "Context Variables should be created at the top module", "properly garbage collected.")
    fact("pc-get-default", C, "Return a value for the context variable for the current context.", "LookupError\n.")
    fact("pc-token", C, "Returns a\nToken\nobject that can be used", "token\nwas used.")
    fact("pc-token-once", C, "The same\ntoken\ncannot be used twice.", "A single token cannot reset a context variable more than once.")
    fact("pc-copy-complexity", C, "The function has an\nO\n(1) complexity", "a lot of them.")
    fact("pc-entered-error", C, "Attempting to enter an already entered context", "re-entered (from any thread).")

    fact("hc-pools", H, "An\nHttpClient\nprovides configuration information", "reusing such connections.")
    fact("hc-sync-async", H, "Requests can be sent either synchronously or asynchronously:", "several asynchronous tasks.")
    fact("hc-interrupted-send", H, "If the operation is interrupted, the default", "asynchronously.")
    fact("hc-async-cancel", H, "The default\nHttpClient\nimplementation returns", "asynchronously.")
    fact("hc-shutdown-now", H, "shutdownNow\npublic", "ever be notified.")
    fact("hc-close", H, "close\npublic", "before this method returns.")

    fact("jf-exists", F, "exists\npublic static\nboolean\nexists", "not exist or its existence cannot be determined.")
    fact("jf-not-exists", F, "notExists\npublic static", "then both methods return\nfalse\n.")
    fact("jf-lines", F, "lines\npublic static\nStream\n<\nString\n>\nlines\n(\nPath\npath,", "after the stream's operations have completed.")
    fact("jf-read-string", F, "readString\npublic static\nString\nreadString\n(\nPath\npath)", "readString(path, StandardCharsets.UTF_8)\n.")
    fact("jf-copy-atomic", F, "Copying a file is not an atomic operation.", "other file system activities.")
    fact("jf-delete", F, "deleteIfExists\npublic", "considered empty when only the special entries exist.")

    case("heldout-q-01", "Python Queue.qsize()>0 或 qsize()<maxsize，是否分别保证随后的 get 与 put 不会阻塞？",
         "都不保证；qsize 是近似值，随后的并发状态可能变化。", [["pq-size"]], "identifier")
    case("heldout-q-02", "Queue.put(block=False, timeout=2) 在队列满时会等两秒吗？默认阻塞参数与正数 timeout 的单位是什么？",
         "非阻塞模式立即尝试，满则 Full，忽略 timeout；默认 block=True/timeout=None，正数 timeout 单位秒。", [["pq-put"]], "condition")
    case("heldout-q-03", "Queue.get 返回就能让 Queue.join 解锁吗？task_done 比放入项数调用得更多会发生什么？",
         "join 等未完成计数归零，需要 task_done 表示处理完成；task_done 过多抛 ValueError。", [["pq-join"], ["pq-task-done"]])
    case("heldout-q-04", "Python 3.13 Queue.shutdown 默认还能读取已有项吗？immediate=True 后 join 解锁是否证明这些项实际处理过？",
         "默认可以 get 排空、逐项 task_done 后正常解锁；立即关闭会排空并减少未完成计数，达到零会解锁，不能证明实际处理过。", [["pq-shutdown-normal"], ["pq-shutdown-immediate"]], "condition")
    case("heldout-q-05", "SimpleQueue.put 的 block 和 timeout 是否真正控制等待，能否因为队列满而常规阻塞？",
         "从不常规阻塞且总能成功，除内存分配等低层错误；block 和 timeout 被忽略，只为兼容 Queue.put。", [["pq-simple-put"]], "identifier")

    case("heldout-c-01", "ContextVar 为什么应定义在模块顶层而不是闭包？ContextVar.get 无当前值时如何按默认值顺序处理？",
         "Context 持强引用，闭包定义会妨碍正确回收；get 优先使用方法默认参数，再用变量声明默认值，否则 LookupError。", [["pc-module-level"], ["pc-get-default"]])
    case("heldout-c-02", "ContextVar.set 返回什么，怎样恢复之前值？同一个 token 可以重复 reset 吗？",
         "返回 Token，传给 reset 恢复该次 set 前的值；同一个 token 不能重复使用。", [["pc-token"], ["pc-token-once"]], "identifier")
    case("heldout-c-03", "copy_context 的复杂度会随 ContextVar 数量线性增长吗？已在其他线程 entered 的 Context 能再次进入吗？",
         "复制复杂度 O(1)；已经 entered 的 Context 不论在哪个线程都不能再次进入，会 RuntimeError，退出后可从任意线程重入。", [["pc-copy-complexity"], ["pc-entered-error"]], "condition")

    case("heldout-h-01", "Java 21 每次 HTTP 操作都 new 一个 HttpClient，与复用实例相比通常怎样影响连接池复用？",
         "实例通常各自管理连接池，池不在实例间共享；每次新建客户端通常阻止连接复用。", [["hc-pools"]], "paraphrase")
    case("heldout-h-02", "HttpClient.send 与 sendAsync 的等待行为和结果类型各是什么？send 被中断后，是否保证请求没有到服务器？",
         "send 阻塞等待响应，sendAsync 立即返回 Future；send 中断后尝试取消并抛 InterruptedException，但请求可能仍发送，取消生效及资源释放时点不保证。", [["hc-sync-async"], ["hc-interrupted-send"]])
    case("heldout-h-03", "JDK 默认 HttpClient.sendAsync 返回的未完成可取消 Future，调用 cancel(true) 会尝试什么？是否保证请求立刻停止或从未发送？",
         "尝试取消 HTTP exchange 以释放资源；不保证何时生效，请求可能仍发到服务端，资源也可能异步释放。", [["hc-async-cancel"]], "identifier")
    case("heldout-h-04", "HttpClient.close 是否等待已有请求完成？shutdownNow 对运行中操作的终止和等待者通知有什么保证？",
         "close 有序关闭并等待操作/客户端终止；shutdownNow 只是尝试立即关闭，运行中操作终止或等待者收到通知均无保证。", [["hc-close"], ["hc-shutdown-now"]], "condition")

    case("heldout-f-01", "Files.exists 返回 false 能确认文件不存在吗？Files.notExists 一定是它的逻辑取反吗？",
         "不能，无法确定存在性也返回 false；notExists 不是补集，无法判断时两者均 false，结果还可能立即过时。", [["jf-exists"], ["jf-not-exists"]], "condition")
    case("heldout-f-02", "Files.lines(path, charset) 是一次把整文件读进 List，还是惰性读取？返回流与打开文件应怎样关闭？",
         "随消费惰性读取，返回流持有打开文件；关闭流会关闭文件，须使用 try-with-resources 或类似结构及时关闭。", [["jf-lines"]], "identifier")
    case("heldout-f-03", "Files.readString(path) 默认用平台编码吗，读取失败时文件是否会关闭？Files.copy 抛 IOException 后目标是否保证完整？",
         "默认 UTF-8，读完或 I/O/运行时错误会关闭；copy 不原子，目标可能不完整或属性没复制完。", [["jf-read-string"], ["jf-copy-atomic"]])
    case("heldout-f-04", "Files.deleteIfExists 对符号链接会删最终目标吗？删除目录是否允许目录包含普通文件？",
         "删链接本身，不删最终目标；目录必须为空，特定实现的专用特殊项例外。", [["jf-delete"]], "condition")

    case("heldout-cross-01", "分别比较 Queue.put 与 SimpleQueue.put：block=False 与 timeout 参数是否都具有相同的等待语义？",
         "Queue 非阻塞满则 Full、忽略 timeout；SimpleQueue 从不常规阻塞，block/timeout 均忽略，除低层错误总成功。", [["pq-put"], ["pq-simple-put"]], "cross_topic")
    case("heldout-cross-02", "分别说明 Files.lines 与 HttpClient.sendAsync 的返回结果：前者是否惰性且需要关闭，后者是否同步等待完整响应？",
         "Files.lines 惰性流持文件，需及时关闭；sendAsync 立即返回 Future，异步取得响应。", [["jf-lines"], ["hc-sync-async"]], "cross_document")
    case("heldout-cross-03", "Python Queue.shutdown(immediate=True) 的 join 解锁是否证明工作完成；Java HttpClient.close 会不会等待已有操作终止？",
         "立即关闭可在没有实际工作完成时使 join 解锁；HttpClient.close 等已有操作完成及客户端终止。", [["pq-shutdown-immediate"], ["hc-close"]], "cross_document")
    case("heldout-cross-04", "Files.exists 与 ContextVar.get 在无法取得信息时默认怎么表现：能否简单把前者 false 和后者无值理解为确定不存在？",
         "exists 在不存在或无法判断时返回 false；ContextVar.get 无当前值按方法默认值、变量默认值处理，否则 LookupError。", [["jf-exists"], ["pc-get-default"]], "cross_document")
    case("heldout-cross-05", "分别说明 HttpClient.sendAsync.cancel(true) 能否立即保证远端请求停止，以及 Files.copy 出错时能否保证目标完整。",
         "默认客户端的可取消 Future 只尝试取消 exchange，不保证生效时点/请求没发送；copy 不原子，目标可能不完整。", [["hc-async-cancel"], ["jf-copy-atomic"]], "cross_document")
    case("heldout-cross-06", "ContextVar.set 返回的 token 能否再次 reset；Queue.task_done 若调用次数超过已放入任务数，会发生什么？",
         "同 token 不可重复 reset；task_done 过多抛 ValueError。", [["pc-token-once"], ["pq-task-done"]], "cross_document")

    for i, (question, reason, doc) in enumerate([
        ("Rehevo 生产 HttpClient 池当前复用了多少连接，P95 建连耗时是多少？", "API 参考没有项目有效配置或测量。", H),
        ("Rehevo Files.copy 错误后的文件损坏率和成功恢复比例是多少？", "官方语义没有项目故障统计分母。", F),
        ("Rehevo 当前 Python Queue 最大容量和过去一周积压峰值是多少？", "模块参考不能确定项目是否采用该模块及运行指标。", Q),
        ("Rehevo 的 ContextVar token 泄漏已经让多少线上用户出错？", "文档没有该项目线上事件证据。", C),
    ], 1):
        case(f"heldout-unavailable-{i:02d}", question, "这些资料没有给出项目实际测量，不能确定。", [], "unanswerable")
        cases[-1].update(answerable=False, sourceDocumentIds=[doc], unanswerableReason=reason,
                         searchedSourceDocumentIds=[d["documentId"] for d in documents])
    for name, rows in (("facts.jsonl", facts), ("test.jsonl", cases)):
        (PACK / name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    manifest = {"schemaVersion": "rehevo-fact-gold-v1", "packId": "new-source-heldout-test-20261002-r1",
                "documents": documents, "factsPath": "facts.jsonl", "factsSha256": digest(PACK / "facts.jsonl"),
                "splits": {"test": {"path": "test.jsonl", "sha256": digest(PACK / "test.jsonl"),
                                     "cases": len(cases), "answerable": sum(row["answerable"] for row in cases)}},
                "frozenAt": reviewed, "humanReviewed": False, "independentReviewer": False,
                "freezeScope": "test-input-after-strategy-freeze-before-first-retrieval",
                "strategyFreezeSha256": strategy_sha, "devManifestSha256": sources["devManifestSha256"],
                "limitations": ["Same Agent knows strategy while authoring; source/topic heldout, not independent blinded review",
                                "Chinese technical questions against English official docs; controlled corpus",
                                "No production users, generation quality, refusal success, or latency causal claim"]}
    (PACK / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    gold = load_gold(PACK / "manifest.json", "agent_verified")
    print(json.dumps({"documents": len(gold.documents), "facts": len(gold.facts), "cases": len(gold.cases),
                      "answerable": sum(row["answerable"] for row in cases), "sha256": digest(PACK / "manifest.json")}), flush=True)


if __name__ == "__main__":
    main()
