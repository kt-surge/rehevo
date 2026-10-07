"""Explicit compact resources to avoid a measured normal-Skill truncation regression."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
texts = {
    'spring.md': '''# Spring 面试重点

## IoC
- 创建与依赖解耦；@Component/@Bean 与第三方库；@Autowired 类型优先/@Resource 名称优先及冲突解析。
- 构造器/Setter/字段注入与测试/不可变性/循环依赖；singleton/prototype/request/session；生命周期：创建→属性→Aware→初始化→销毁；线程安全。

## AOP 与 MVC
- 切面/切点/通知/连接点；AOP 动态代理、AspectJ 织入；自调用默认不经过代理。
- MVC：DispatcherServlet、HandlerMapping→HandlerAdapter→ViewResolver；@ControllerAdvice/@ExceptionHandler；拦截器/过滤器。

## 事务（Spring Framework 6+）
- @Transactional：传播/隔离/rollbackFor；七种传播如 REQUIRED/REQUIRES_NEW/NESTED。
- 类代理默认支持 protected/package-visible，接口代理须公开接口方法；外部经代理且未设 publicMethodsOnly。private/final 限制看代理方式。
- 自调用无新的事务拦截，但可参与外层事务；初始化代理未就绪、吞异常、跨线程均须另析。
- 默认 RuntimeException/Error 回滚，checked Exception 不回滚；rollbackFor 或全局规则可改变结果。

## 循环依赖与 Boot
- setter 三级缓存：singletonObjects/earlySingletonObjects/singletonFactories；构造器循环依赖可 @Lazy；Boot 2.6+ 默认禁止循环依赖。
- 自动配置/Starter：@SpringBootApplication/@EnableAutoConfiguration、spring.factories/Imports、@ConditionalOnClass/@ConditionalOnMissingBean。
- 默认命令行 > 环境变量 > 配置数据；同位置 properties 优于 YAML 为独立规则；自定义 Environment/测试属性另查。

追问：项目 AOP。
''',
    'mysql.md': '''# MySQL 面试重点（MySQL 8.4 / InnoDB）

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
'''
}
print(json.dumps(dict(chars={name:len(text) for name,text in texts.items()}, externalModelCalls=0)))
assert sum(map(len, texts.values())) <= 1750, 'Keep the normal production references below 6000 characters'
for name, content in texts.items():
    assert len(content) < 1500
    if '--preview' not in sys.argv:
        (ROOT / 'app/src/main/resources/skills/_shared/references' / name).write_text(content, encoding='utf-8', newline='\n')
