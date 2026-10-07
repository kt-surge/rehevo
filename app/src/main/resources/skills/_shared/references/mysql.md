# MySQL 面试重点（MySQL 8.4 / InnoDB）

## 索引与执行计划
- B+ 树有序/范围/磁盘访问；覆盖索引/回表、最左前缀、索引下推。
- 函数/转换、LIKE 前导通配、缺前缀不等于绝不使用索引；OR 可 Index Merge、覆盖扫描另看计划。
- 前缀列等值时后续列可排序；多个前缀都等值，两种列序均可能支持。低基数在前不必然错；区分能力与成本，用 EXPLAIN 的 type/key/key_len/rows/Extra（filesort/temporary）验证。

## 事务、MVCC 与锁
- ACID、RU/RC/RR/SERIALIZABLE；默认 RR 普通一致性读为快照，锁定读/写按条件与索引分析。
- MVCC：trx_id/roll_pointer、Undo 版本链、ReadView。
- RR 范围/非唯一条件通常涉及 Next-Key/Gap；完整唯一键等值命中现存单行通常 Record Lock。复合键仅部分列、记录不存在另查，不能把所有当前读说成有间隙锁。
- 表/行锁、Record/Gap/Next-Key、IS/IX 与 S/X 兼容；固定顺序/短事务减少死锁。

## 日志与优化
- InnoDB/MyISAM 的事务/锁/外键/恢复；Redo（WAL/恢复）、Undo（回滚/MVCC）、Binlog（复制/归档）及两阶段提交。
- 慢日志/pt-query-digest、垂直/水平拆分/ShardingSphere、游标分页/延迟关联/主键子查询、冷热/读写分离。
- 追问：SQL 优化、并发锁冲突、主从延迟/一致性。

依据：MySQL 8.4 官方文档。
