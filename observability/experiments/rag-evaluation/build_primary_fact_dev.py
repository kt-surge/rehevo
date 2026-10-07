"""Build new primary-document dev Gold from explicitly audited source facts.

Question/reference/conditions are authored here. This is Agent review, not
human or independent review. No historical chunk-index labels are reused.
"""
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
ORIGINAL_PACK = ROOT / "data/local/fact-gold-v1-20261001"
PACK = ROOT / "data/local/fact-gold-v1-20261001-r1"
PG = "postgres17-isolation"
TG = "postgres17-trigrams"
RD = "redis-xreadgroup"
SP = "spring-transaction-annotations"


def main():
    if PACK.exists():
        raise ValueError("Gold pack is frozen; do not overwrite. Create an explicit new version for corrections")
    PACK.mkdir()
    for source in ORIGINAL_PACK.iterdir():
        if source.suffix in (".html", ".txt") or source.name == "sources.manifest.json":
            shutil.copyfile(source, PACK / source.name)
    documents = json.loads((PACK / "sources.manifest.json").read_text(encoding="utf-8"))["documents"]
    texts = {doc["documentId"]: (PACK / doc["canonicalPath"]).read_text(encoding="utf-8") for doc in documents}
    facts = []
    cases = []

    def fact(fact_id, doc, head, tail):
        text = texts[doc]
        if text.count(head) != 1:
            raise ValueError(f"Fact anchor is missing/ambiguous: {fact_id}")
        start = text.index(head)
        end = text.index(tail, start) + len(tail)
        facts.append({"factId": fact_id, "documentId": doc, "start": start, "end": end,
                      "exactQuote": text[start:end]})
        return fact_id

    def case(case_id, question, answer, requirements, category="multi_requirement", docs=None):
        used = list(dict.fromkeys(fact_id for _, groups in requirements for group in groups for fact_id in group))
        references = list(dict.fromkeys(fact_id for _, groups in requirements for fact_id in groups[0]))
        fact_documents = {item["factId"]: item["documentId"] for item in facts}
        cases.append({"id": case_id, "split": "dev", "question": question, "questionLanguage": "zh",
                      "sourceLanguage": "en", "category": category, "answerable": bool(requirements),
                      "sourceDocumentIds": docs or list(dict.fromkeys(fact_documents[item] for item in used)),
                      "requirements": [{"id": name, "alternatives": [{"factIds": group} for group in groups]}
                                       for name, groups in requirements],
                      "referenceAnswer": answer, "referenceFactIds": references})

    def req(name, *fact_ids):
        return name, [list(fact_ids)]

    def unavailable(case_id, question, reason, doc):
        case(case_id, question, "现有资料没有给出该信息，不能据此确定。", [], "unanswerable", [doc])
        cases[-1]["unanswerableReason"] = reason
        cases[-1]["searchedSourceDocumentIds"] = [PG, TG, RD, SP]

    fact("pg-rc-default", PG, "Read Committed\nis the default isolation level in", "PostgreSQL\n.")
    fact("pg-rc-statement-snapshot", PG, "When a transaction uses this isolation level,", "query begins to run.")
    fact("pg-rc-two-selects", PG, "Also note that two successive", "starts.")
    fact("pg-ru-mapping", PG, ", you can request any of the four standard transaction isolation levels,", "architecture.")
    fact("pg-rr-snapshot", PG, "This level is different from Read Committed in that", "transaction started.")
    fact("pg-rr-no-phantom", PG, "The table also shows that PostgreSQL's Repeatable Read implementation", "phantom reads.")
    fact("pg-rr-retry", PG, "When an application receives this error message,", "from the beginning.")
    fact("pg-sequence", PG, "changes made to a sequence", "changes aborts.")
    fact("pg-serialization-state", PG, "It is important that an environment which uses this technique", "serialization anomalies.")
    fact("pg-predicate-no-block", PG, "To guarantee true serializability", "causing a deadlock.")

    case("primary-dev-pg-01", "PostgreSQL 17 默认隔离级别是什么？在该级别下，普通 SELECT 的快照按什么时点建立，同一事务两次 SELECT 何时可能看到不同结果？",
         "默认 Read Committed；不带 FOR UPDATE/SHARE 的 SELECT 以查询开始时的已提交数据为快照；若其他事务在两次查询开始之间提交修改，两次结果可能不同。",
         [req("default-isolation", "pg-rc-default"), req("ordinary-select-snapshot", "pg-rc-statement-snapshot"), req("two-select-condition", "pg-rc-two-selects")])
    case("primary-dev-pg-02", "PostgreSQL 17 请求 Read Uncommitted 时，内部真正提供的行为是什么，为什么不是独立的第四种实现？",
         "Read Uncommitted 的行为等同于 Read Committed；内部只有三种不同实现，这与其多版本并发控制架构的映射有关。",
         [req("mapping-and-reason", "pg-ru-mapping")], "paraphrase")
    case("primary-dev-pg-03", "PostgreSQL 17 的 Repeatable Read 快照从哪个语句的开始固定？它是否允许幻读？",
         "从事务中第一条非事务控制语句开始固定快照，后续 SELECT 看到同一快照；其实现不允许幻读。",
         [req("snapshot-start", "pg-rr-snapshot"), req("phantom-guarantee", "pg-rr-no-phantom")])
    case("primary-dev-pg-04", "PostgreSQL Repeatable Read 因并发更新收到序列化错误后，应只重试失败 SQL，还是怎样重试？",
         "应中止当前事务，从头重试整个事务，不能仅重试失败语句。", [req("retry-scope", "pg-rr-retry")], "condition")
    case("primary-dev-pg-05", "PostgreSQL 中序列值的修改何时对其他事务可见？修改序列的事务中止后，序列变化会回滚吗？",
         "序列修改立即对其他事务可见，即使修改它的事务中止，也不会回滚。", [req("visibility-and-rollback", "pg-sequence")], "condition")
    case("primary-dev-pg-06", "PostgreSQL Serializable 的序列化失败 SQLSTATE 是什么？用于发现依赖的 predicate locks 是否阻塞并导致死锁？",
         "序列化失败 SQLSTATE 为 40001；这些谓词锁用于跟踪依赖，不造成阻塞，因此不会造成死锁。",
         [req("serialization-sqlstate", "pg-serialization-state"), req("predicate-lock-behavior", "pg-predicate-no-block")], "identifier")
    unavailable("primary-dev-pg-07", "资料能确定 Rehevo 生产数据库当前 max_pred_locks_per_transaction 的实际配置值吗？",
                "资料讨论参数作用，但没有 Rehevo 生产数据库配置记录。", PG)
    unavailable("primary-dev-pg-08", "Rehevo 改用 Serializable 后，线上 TPS 实测提升了百分之多少？",
                "四份官方文档均没有 Rehevo 线上前后性能实验。", PG)

    fact("tg-similarity-threshold", TG, "pg_trgm.similarity_threshold\n(\nreal\n)\n#", "(default is 0.3).")
    fact("tg-word-threshold", TG, "pg_trgm.word_similarity_threshold\n(\nreal\n)\n#", "(default is 0.6).")
    fact("tg-strict-threshold", TG, "pg_trgm.strict_word_similarity_threshold\n(\nreal\n)\n#", "(default is 0.5).")
    fact("tg-signature", TG, "gist_trgm_ops\nGiST opclass approximates a set of trigrams as a bitmap signature.", "a larger index.")
    fact("tg-knn", TG, "SELECT t, t <-> '", "wanted.")
    fact("tg-pattern-condition", TG, "For both\nLIKE\nand regular-expression searches,", "full-index scan.")
    fact("tg-index-support", TG, "module provides GiST and GIN index operator classes", "equality operator.")
    fact("tg-word-boundaries", TG, "Thus, the\nstrict_word_similarity\nfunction is useful", "parts of words.")

    case("primary-dev-tg-01", "pg_trgm 的 similarity_threshold、word_similarity_threshold、strict_word_similarity_threshold 默认值及各自控制的运算符是什么？",
         "similarity_threshold 默认 0.3，控制 %；word_similarity_threshold 默认 0.6，控制 <% 和 %>；strict_word_similarity_threshold 默认 0.5，控制 <<% 和 %>>。",
         [req("similarity-operator-default", "tg-similarity-threshold"), req("word-operator-default", "tg-word-threshold"), req("strict-operator-default", "tg-strict-threshold")], "identifier")
    case("primary-dev-tg-02", "gist_trgm_ops 的 siglen 单位、默认值和合法范围是什么？调长签名带来什么收益与代价？",
         "siglen 按字节计，默认 12，合法范围 1–2024；更长签名使检索更精确、减少索引和堆页扫描，但索引会更大。",
         [req("signature-parameters-and-tradeoff", "tg-signature")], "condition")
    case("primary-dev-tg-03", "按 t <-> 'word' 排序并 LIMIT 10 的最近文本查询，GiST 和 GIN 是否都能高效实现？",
         "文档中的该距离排序 LIMIT 查询可由 GiST 高效实现，GIN 不能同样高效实现；只取少量最近匹配时通常优于先过滤再排序的写法。",
         [req("distance-order-index-support", "tg-knn")], "identifier")
    case("primary-dev-tg-04", "pg_trgm 的 LIKE 或正则检索，什么条件下会退化成全索引扫描？",
         "当模式无法提取任何 trigram 时，会退化为全索引扫描。", [req("pattern-no-trigrams", "tg-pattern-condition")], "condition")
    case("primary-dev-tg-05", "pg_trgm 的 GiST/GIN 索引是否支持不等比较？用于等号比较时，是否保证比普通 B-tree 更高效？",
         "不支持不等比较；支持等号查询，但用于等号时未必比常规 B-tree 高效。", [req("inequality-and-equality-limit", "tg-index-support")], "condition")
    case("primary-dev-tg-06", "需要按完整单词边界匹配和只匹配单词片段时，strict_word_similarity 与 word_similarity 应如何选？",
         "完整单词边界相似性使用 strict_word_similarity；词的局部片段相似性更适合 word_similarity。",
         [req("function-boundary-choice", "tg-word-boundaries")], "paraphrase")
    unavailable("primary-dev-tg-07", "pg_trgm 对 Rehevo 全部中文知识库的检索 Recall 实测是多少？",
                "资料介绍算法和索引，没有该项目中文语料金标实验。", TG)
    unavailable("primary-dev-tg-08", "在 Rehevo 当前服务器上 siglen=64 相比 siglen=12 的真实 P95 降低了多少毫秒？",
                "文档给出一般取舍，没有指定服务器的配对延迟记录。", TG)

    fact("rd-pel", RD, "When you read with\nXREADGROUP", "not yet acknowledged.")
    fact("rd-ack-pending", RD, "The client will have to acknowledge the message processing using", "XPENDING\ncommand.")
    fact("rd-new-messages", RD, "When the special\n>\nid is specified without", "just new messages.")
    fact("rd-history-ignored", RD, "Any other ID, that is, 0 or any other valid ID", "are ignored.")
    fact("rd-crash-recovery", RD, "However the example code above is", "processing new things.")
    fact("rd-noack-normal", RD, "The\nNOACK\nsubcommand can be used to avoid adding the message to the PEL", "when it is read.")
    fact("rd-noack-claim-exception", RD, "When used together with\nCLAIM", "retrieved pending entries.")
    fact("rd-count", RD, "COUNT count\nThe maximum number", "per stream.")
    fact("rd-block", RD, "BLOCK milliseconds\nBlock for up to", "blocks indefinitely.")
    fact("rd-delete-pending", RD, "What happens when a pending message is deleted?", "respective data.")

    case("primary-dev-rd-01", "XREADGROUP 的 PEL 保存什么状态？成功处理后如何从 PEL 移除，又用什么命令查看？",
         "PEL 保存已交付但未确认的消息 ID；成功处理后用 XACK 移除对应 pending 条目，用 XPENDING 查看 PEL。",
         [req("pel-state", "rd-pel"), req("ack-and-inspection", "rd-ack-pending")], "identifier")
    case("primary-dev-rd-02", "XREADGROUP 不带 CLAIM 时，STREAMS 的 ID 用 > 与用 0 有何区别？历史读取时 BLOCK、NOACK、CLAIM 是否生效？",
         "> 只读取未交付给任何消费者的新消息；0 返回本消费者 ID 大于 0 的未确认历史消息。使用非 > 的历史 ID 时 BLOCK、NOACK、CLAIM 被忽略。",
         [req("new-message-condition", "rd-new-messages"), req("history-scope-and-ignored-options", "rd-history-ignored")], "condition")
    case("primary-dev-rd-03", "按官方 XREADGROUP 示例，消费者处理中崩溃后如何恢复自己的未确认消息，何时重新使用 > 读取？",
         "未完成确认的消息仍在 PEL；恢复时先用 ID 0 读取并处理、确认自己的历史消息，直到返回空，再改用 > 消费新消息。",
         [req("crash-pending-recovery-and-switch", "rd-crash-recovery")], "paraphrase")
    case("primary-dev-rd-04", "根据冻结的 Redis 文档，NOACK 对普通新消息意味着什么？与 CLAIM 同用时，它是否也适用于取回的 pending 消息？",
         "普通新消息不加入 PEL，等价于读取时立即确认，适用于可接受偶发丢失的场景；与 CLAIM 同用时，NOACK 不适用于取回的 pending 条目。",
         [req("noack-normal-semantics", "rd-noack-normal"), req("claim-exception", "rd-noack-claim-exception")], "condition")
    case("primary-dev-rd-05", "XREADGROUP 的 COUNT 是每个 stream 还是所有 stream 的总条数上限？BLOCK 0 表示什么？",
         "COUNT 是每个 stream 的最大返回条数；BLOCK 0 表示无限期阻塞。",
         [req("count-budget-scope", "rd-count"), req("zero-block-meaning", "rd-block")], "identifier")
    case("primary-dev-rd-06", "删除或裁剪消息且未指定 DELREF/ACKED 清理 pending 引用时，PEL 中的 ID 与消息负载会怎样？重新读取这些 PEL 条目返回什么？",
         "PEL 保留被删除消息的 ID，但实际负载不再可用；读取这些 pending 条目的数据位置返回 null。",
         [req("deleted-pending-ids-and-payload", "rd-delete-pending")], "condition")
    unavailable("primary-dev-rd-07", "这些资料能确定 Rehevo 当前生产 Redis Stream 消费器的重试次数和重领 idle 阈值吗？",
                "命令参考没有项目消费者实现或实际运行配置。", RD)
    unavailable("primary-dev-rd-08", "Rehevo 消费器在实际 Redis 故障切换实验中丢了多少条消息？",
                "四份文档中没有项目故障注入及消息丢失测量记录。", RD)

    fact("sp-proxy-self-call", SP, "In proxy mode (which is the default),", "@PostConstruct\nmethod.")
    fact("sp-method-visibility", SP, "As of 6.0,", "proxy are intercepted.")
    fact("sp-derived-precedence", SP, "The most derived location takes precedence", "at the class level.")
    fact("sp-class-inheritance", SP, "Used at the class level as above,", "subclass-level annotation.")
    fact("sp-default-rollback", SP, "The default\n@Transactional\nsettings are as follows:", "not.")
    fact("sp-all-exceptions", SP, "As of 6.2, you can globally change the default rollback behavior", "any checked exception.")
    fact("sp-specific-rollback-rules", SP, "Note that transaction-specific rollback rules", "unspecified exceptions.")
    fact("sp-default-settings", SP, "The default\n@Transactional\nsettings are as follows:", "timeouts are not supported.")
    fact("sp-timeout-applicability", SP, "timeout\nint\n(in seconds of granularity)", "REQUIRES_NEW\n.")
    fact("sp-annotation-activation", SP, "However, the mere presence", "management at runtime.")
    fact("sp-context-scope", SP, "@EnableTransactionManagement\nand\n<tx:annotation-driven/>", "not in your services.")

    case("primary-dev-sp-01", "Spring 默认 proxy 事务模式中，同一个对象的方法内部调用 @Transactional 方法会开启该注解对应的事务吗？可以在 @PostConstruct 中依赖这个代理行为吗？",
         "不会由这种内部调用触发注解对应的事务，默认只拦截经过代理的外部调用；代理必须完全初始化，不应在 @PostConstruct 初始化代码中依赖该行为。",
         [req("proxy-call-and-initialization-condition", "sp-proxy-self-call")], "condition")
    case("primary-dev-sp-02", "Spring 6.0 起，class-based proxy 对 protected/package-visible 事务方法与 interface-based proxy 的 public/接口声明要求有何区别？",
         "class-based proxy 默认也可支持 protected 或包可见事务方法；interface-based proxy 的事务方法必须 public 且在被代理接口中定义；两者均只拦截经过代理的外部调用。",
         [req("version-proxy-visibility-and-external-call", "sp-method-visibility")], "condition")
    case("primary-dev-sp-03", "Spring 类级和方法级事务设置冲突时优先哪层？子类类级 @Transactional 会自动覆盖祖先类继承来的方法吗？",
         "更具体的方法级设置优先；子类类级注解不自动应用于祖先类的方法，需要在本地重新声明继承方法才参与子类类级注解。",
         [req("derived-settings-priority", "sp-derived-precedence"), req("ancestor-method-redeclaration", "sp-class-inheritance")])
    case("primary-dev-sp-04", "没有自定义回滚规则时 Spring 对 RuntimeException、Error 和 checked Exception 默认如何处理？6.2 起如何全局调整为所有异常回滚，事务专用规则如何影响默认规则？",
         "默认 RuntimeException 和 Error 触发回滚，checked Exception 不触发；6.2 起可用 @EnableTransactionManagement(rollbackOn=ALL_EXCEPTIONS) 改全局默认。事务专用回滚规则覆盖默认行为，未指定的异常仍保留所选默认规则。",
         [req("default-exception-types", "sp-default-rollback"), req("version-global-all-exceptions", "sp-all-exceptions"), req("specific-rule-priority", "sp-specific-rollback-rules")], "identifier")
    case("primary-dev-sp-05", "Spring @Transactional 默认传播与隔离设置是什么？timeout 的单位和适用传播范围是什么，未指定超时时如何取值？",
         "默认 PROPAGATION_REQUIRED 和 ISOLATION_DEFAULT；timeout 按秒计，只适用于 REQUIRED 或 REQUIRES_NEW。未指定时取底层事务系统默认超时，系统不支持则没有超时。",
         [req("defaults-and-timeout-fallback", "sp-default-settings"), req("timeout-unit-and-propagation", "sp-timeout-applicability")], "identifier")
    case("primary-dev-sp-06", "仅写 @Transactional 能激活事务吗？如果只在 DispatcherServlet 的 WebApplicationContext 启用 annotation-driven，是否会扫描另一上下文的 Service？",
         "注解只是元数据，需要相应运行时事务基础设施；annotation-driven 只查自身应用上下文的 Bean，放在 DispatcherServlet 的 WebApplicationContext 时只检查该上下文的 Controller，不会据此检查另一上下文的 Service。",
         [req("metadata-needs-activation", "sp-annotation-activation"), req("application-context-boundary", "sp-context-scope")])
    unavailable("primary-dev-sp-07", "官方注解资料能确定 Rehevo 当前线上选择的 TransactionManager Bean 名称和连接池容量吗？",
                "官方说明不是项目运行配置，也没有实际 Bean/连接池记录。", SP)
    unavailable("primary-dev-sp-08", "Rehevo 线上有多少次事务因为 checked Exception 没有回滚，具体错误率是多少？",
                "文档解释默认语义，没有项目线上异常事件与错误率分母。", SP)

    case("primary-dev-cross-01", "未覆盖 Spring 事务默认设置、使用 PostgreSQL 17 默认数据库隔离时，@Transactional 的传播、实际数据库默认隔离和 checked Exception 默认回滚分别是什么？",
         "Spring 默认传播 REQUIRED，隔离设置 ISOLATION_DEFAULT；PostgreSQL 17 数据库默认 Read Committed；没有自定义回滚规则时 checked Exception 默认不触发回滚。",
         [req("spring-transaction-defaults", "sp-default-settings"), req("database-default-isolation", "pg-rc-default"), req("checked-exception-rollback-default", "sp-default-rollback")], "cross_document")
    case("primary-dev-cross-02", "后台消费者经 Spring 事务代理处理 Redis Stream 消息：分别说明 XREADGROUP 消费者崩溃后恢复自己的 PEL 的顺序，以及未自定义规则时哪些异常触发 Spring 事务回滚。",
         "Redis 恢复先用 ID 0 处理并确认自己的 pending，返回空后切回 > 读取新消息；Spring 默认由 RuntimeException 或 Error 触发回滚，checked Exception 不触发。",
         [req("consumer-own-pending-recovery", "rd-crash-recovery"), req("default-transaction-rollback", "sp-default-rollback")], "cross_document")

    reviewed_at = datetime.now(timezone.utc).isoformat()
    for row in cases:
        row["review"] = {"status": "agent_verified", "reviewerType": "agent",
                         "reviewer": "codex-source-audit-20261001", "reviewedAt": reviewed_at,
                         "method": "Question, conditions, reference answer and exact source spans checked during authoring; same Agent self-review"}
        row["semanticReviewNotes"] = "逐项保留问题中的必要属性及条件；不可回答限于所冻结的四份资料，不推断任何线上成绩。"
    for name, rows in (("facts.jsonl", facts), ("dev.jsonl", cases)):
        (PACK / name).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    manifest = {"schemaVersion": "rehevo-fact-gold-v1", "packId": "primary-doc-facts-dev-20261001-r1",
                "documents": documents, "factsPath": "facts.jsonl", "factsSha256": digest(PACK / "facts.jsonl"),
                "splits": {"dev": {"path": "dev.jsonl", "sha256": digest(PACK / "dev.jsonl"),
                                     "cases": len(cases), "answerable": sum(row["answerable"] for row in cases)}},
                "frozenAt": reviewed_at, "freezeScope": "development-pack-only",
                "humanReviewed": False, "testMaterialsPrepared": False,
                "supersedesManifestSha256": digest(ORIGINAL_PACK / "manifest.json"),
                "revisionReason": "Pre-retrieval semantic review included NOACK, gist_trgm_ops and @Transactional subjects in source fact spans. Original pack retained, no score was produced.",
                "limitations": ["Chinese questions against English original documentation",
                                "Public-document controlled corpus, not real user knowledge or production outcomes",
                                "Same Agent authoring and semantic self-review, no independent/human review",
                                "No new test split exists yet; dev scores cannot be final generalization evidence",
                                "Redis source is the retrieval-date latest command reference, not the project's deployed Redis version"]}
    (PACK / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    gold = load_gold(PACK / "manifest.json", "agent_verified")
    print(json.dumps({"documents": len(gold.documents), "facts": len(gold.facts),
                      "cases": len(gold.cases), "answerable": sum(row["answerable"] for row in cases),
                      "goldManifestSha256": digest(PACK / "manifest.json"), "review": "agent_verified",
                      "testMaterialsPrepared": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
