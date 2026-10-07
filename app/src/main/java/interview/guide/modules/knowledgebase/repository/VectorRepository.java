package interview.guide.modules.knowledgebase.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<Document> lexicalSearch(String query, List<Long> knowledgeBaseIds, int topK) {
        if (query == null || query.isBlank() || knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        String placeholders = String.join(",", knowledgeBaseIds.stream().map(id -> "?").toList());
        String sql = """
            SELECT id::text, content, metadata::text, 1 - (? <<-> content) AS lexical_score
            FROM vector_store
            WHERE metadata->>'kb_id' IN (%s)
            ORDER BY ? <<-> content, id
            LIMIT ?
            """.formatted(placeholders);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query);
        knowledgeBaseIds.stream().map(String::valueOf).forEach(parameters::add);
        parameters.add(query);
        parameters.add(topK);
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> {
            Map<String, Object> metadata = parseMetadata(resultSet.getString("metadata"));
            double lexicalScore = resultSet.getDouble("lexical_score");
            metadata.put("retrieval_lexical_score", lexicalScore);
            return Document.builder()
                .id(resultSet.getString("id"))
                .text(resultSet.getString("content"))
                .metadata(metadata)
                .score(lexicalScore)
                .build();
        }, parameters.toArray());
    }

    /**
     * 一次批量读取已命中分块的相邻 Chunk。
     *
     * <p>调用方只能传入已经通过检索选中的知识库与分块序号；本方法不执行语义检索，
     * 也不会跨知识库扩展上下文。</p>
     */
    public List<Document> findChunksByKnowledgeBaseAndIndexes(Map<Long, Set<Integer>> indexesByKnowledgeBase) {
        if (indexesByKnowledgeBase == null || indexesByKnowledgeBase.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Integer>> normalized = new LinkedHashMap<>();
        indexesByKnowledgeBase.forEach((knowledgeBaseId, indexes) -> {
            if (knowledgeBaseId == null || indexes == null) {
                return;
            }
            List<Integer> validIndexes = indexes.stream()
                .filter(index -> index != null && index >= 0)
                .distinct()
                .sorted()
                .toList();
            if (!validIndexes.isEmpty()) {
                normalized.put(knowledgeBaseId, validIndexes);
            }
        });
        if (normalized.isEmpty()) {
            return List.of();
        }

        List<Object> parameters = new ArrayList<>();
        List<String> predicates = new ArrayList<>();
        normalized.forEach((knowledgeBaseId, indexes) -> {
            String placeholders = String.join(",", indexes.stream().map(index -> "?").toList());
            predicates.add("(metadata->>'kb_id' = ? AND (metadata->>'chunk_index')::int IN (" + placeholders + "))");
            parameters.add(knowledgeBaseId.toString());
            parameters.addAll(indexes);
        });
        String sql = """
            SELECT id::text, content, metadata::text
            FROM vector_store
            WHERE %s
            ORDER BY metadata->>'kb_id', (metadata->>'chunk_index')::int, id
            """.formatted(String.join(" OR ", predicates));
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> Document.builder()
            .id(resultSet.getString("id"))
            .text(resultSet.getString("content"))
            .metadata(parseMetadata(resultSet.getString("metadata")))
            .build(), parameters.toArray());
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("无法解析向量文档元数据", e);
        }
    }

    public void initializeLexicalSearchSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS vector_store_content_trgm_idx
            ON vector_store USING GIST (content gist_trgm_ops(siglen=64))
            """);
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    /**
     * 查询指定知识库当前已提升为正式状态的向量分块数。
     *
     * <p>向量化任务使用临时 {@code kb_id} 写入，只有提升成功后才会变成正式知识库 ID；
     * 因此这里只统计正式记录，避免将失败任务的临时分块写进页面统计。</p>
     *
     * @param knowledgeBaseId 知识库ID
     * @return 正式向量分块数
     */
    public int countByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = """
            SELECT COUNT(*)
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        try {
            Integer count = jdbcTemplate.queryForObject(
                sql, Integer.class, knowledgeBaseId.toString(), knowledgeBaseId);
            return count != null ? count : 0;
        } catch (Exception e) {
            log.error("查询知识库向量分块数失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "查询知识库向量分块数失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量数据。
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量数据: jobId={}, 删除行数={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量数据失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量数据失败");
        }
    }

  /** 在提升事务内锁住父文档；删除也需要这行的锁，避免提升后出现孤儿向量。 */
  public boolean lockExistingKnowledgeBase(Long knowledgeBaseId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "向量提升必须在事务内校验父文档");
    }
    return Boolean.TRUE.equals(jdbcTemplate.query(
        "SELECT id FROM knowledge_bases WHERE id = ? FOR UPDATE",
        resultSet -> { return resultSet.next(); }, knowledgeBaseId));
  }

  public record VectorTaskState(String generation, VectorStatus status) {}

  /** 仅在有效执行的提升事务内，清理同一父文档其他已失权 job 的临时块。 */
  public int deleteSupersededPendingVectors(Long knowledgeBaseId, String currentJobId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "遗留临时向量清理必须位于有效提升事务中");
    }
    return jdbcTemplate.update("""
        DELETE FROM vector_store
        WHERE metadata->>'kb_target_id' = ?
          AND metadata->>'kb_vector_job_id' IS NOT NULL
          AND metadata->>'kb_vector_job_id' <> ?
        """, knowledgeBaseId.toString(), currentJobId);
  }

  /** 由提升服务在父文档行锁内读取当前请求版本。 */
  public Optional<VectorTaskState> findVectorTaskState(Long knowledgeBaseId) {
    return jdbcTemplate.query("SELECT vector_generation, vector_status FROM knowledge_bases WHERE id = ?",
        (row, number) -> new VectorTaskState(row.getString(1), VectorStatus.valueOf(row.getString(2))),
        knowledgeBaseId).stream().findFirst();
  }

  /** 与正式向量替换处于同一事务，状态拒绝写入时向量替换也回滚。 */
  public int completeVectorTask(Long knowledgeBaseId, String generation, int chunkCount) {
    return jdbcTemplate.update("UPDATE knowledge_bases SET vector_status = 'COMPLETED', vector_error = NULL, "
        + "chunk_count = ? WHERE id = ? AND vector_generation = ? AND vector_status <> 'COMPLETED'",
        chunkCount, knowledgeBaseId, generation);
  }

    /**
     * 将临时向量任务提升为当前知识库的正式向量数据。
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
            UPDATE vector_store
            SET metadata = (jsonb_set(
                    metadata::jsonb,
                    '{kb_id}',
                    to_jsonb(?::text),
                    true
                ) - 'kb_vector_job_id' - 'kb_target_id')::json
            WHERE metadata->>'kb_vector_job_id' = ?
            """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量数据已提升为正式数据: kbId={}, jobId={}, 更新行数={}",
                knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量数据失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量数据失败");
        }
    }
}
