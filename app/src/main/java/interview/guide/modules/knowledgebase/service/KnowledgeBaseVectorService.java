package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.DocumentChunkingService;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.model.VectorTaskExecutionDTO;
import interview.guide.modules.knowledgebase.repository.VectorTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private static final String METADATA_CHUNK_INDEX = "chunk_index";
    private final VectorStore vectorStore;
    private final DocumentChunkingService chunkingService;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final ApplicationMetrics applicationMetrics;
    private final VectorTaskRepository durableTasks;

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        ApplicationMetrics applicationMetrics,
        DocumentChunkingService chunkingService,
        VectorTaskRepository durableTasks
    ) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.applicationMetrics = applicationMetrics;
        this.chunkingService = chunkingService;
        this.durableTasks = durableTasks;
    }

    public KnowledgeBaseVectorService(VectorStore store, VectorRepository vectors,
        TransactionalExecutor transactions, ApplicationMetrics metrics, DocumentChunkingService chunking) {
        this(store, vectors, transactions, metrics, chunking, null);
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, VectorRepository vectorRepository) {
        this(vectorStore, vectorRepository, null, new ApplicationMetrics(null),
            new DocumentChunkingService(new DocumentChunkingProperties()));
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public boolean vectorizeAndStore(Long knowledgeBaseId, String content, String generation) {
        return vectorizeAndStore(knowledgeBaseId, content, generation, null);
    }

    public boolean vectorizeAndStore(Long knowledgeBaseId, String content, String generation,
        DocumentChunkingProperties snapshot) {
        return vectorizeAndStore(knowledgeBaseId, content, generation, snapshot, null, () -> true);
    }

    public boolean vectorizeAndStore(Long knowledgeBaseId, String content, String generation,
        DocumentChunkingProperties snapshot, VectorTaskExecutionDTO execution, BooleanSupplier permit) {
        long startNanos = System.nanoTime();
        String jobId = null;
        int totalChunks = 0;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            if (generation == null || generation.isBlank()) {
              throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量任务缺少请求版本");
            }
            if (content == null || content.isBlank()) {
              throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量内容不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}",
                knowledgeBaseId, jobId, content.length());

            // 1. 将文本分块
            List<Document> chunks = (snapshot == null ? chunkingService : new DocumentChunkingService(snapshot)).split(content);
            // TokenTextSplitter 的输出元数据不承诺包含稳定的分块序号。
            // 显式持久化它，才能把一次检索结果与 Gold 的文档/Chunk 证据主键一一对应。
            applyChunkIndexes(chunks);
            
            log.info("文本分块完成: {} 个chunks", chunks.size());
            
            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);
            chunks.forEach(chunk -> chunk.getMetadata().put("kb_generation", generation));

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                if (!permit.getAsBoolean()) {
                  cleanupPendingVectorJob(knowledgeBaseId, jobId);
                  applicationMetrics.recordRagVectorization(System.nanoTime() - startNanos,
                      totalChunks, ApplicationMetrics.Outcome.SKIPPED);
                  return false;
                }
                vectorStore.add(batch);
            }
            if (!activateVectorJob(knowledgeBaseId, jobId, generation, totalChunks, execution, permit)) {
              log.info("请求已过期、已完成或父文档已删除，放弃提升: kbId={}, jobId={}", knowledgeBaseId, jobId);
              applicationMetrics.recordRagVectorization(
                  System.nanoTime() - startNanos, totalChunks, ApplicationMetrics.Outcome.SKIPPED);
              return false;
            }
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
            applicationMetrics.recordRagVectorization(
                System.nanoTime() - startNanos, totalChunks, ApplicationMetrics.Outcome.SUCCESS
            );
            return true;
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            applicationMetrics.recordRagVectorization(
                System.nanoTime() - startNanos, totalChunks, ApplicationMetrics.Outcome.FAILURE
            );
            if (e instanceof BusinessException businessException) {
              throw businessException;
            }
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage(), e);
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
        });
    }

    private void applyChunkIndexes(List<Document> chunks) {
        for (int index = 0; index < chunks.size(); index++) {
            chunks.get(index).getMetadata().put(METADATA_CHUNK_INDEX, index);
        }
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        long startNanos = System.nanoTime();
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                applicationMetrics.recordRagRetrieval(
                    System.nanoTime() - startNanos,
                    ApplicationMetrics.RetrievalPath.PRIMARY,
                    0,
                    ApplicationMetrics.Outcome.SUCCESS
                );
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            applicationMetrics.recordRagRetrieval(
                System.nanoTime() - startNanos,
                ApplicationMetrics.RetrievalPath.PRIMARY,
                limitedResults.size(),
                ApplicationMetrics.Outcome.SUCCESS
            );
            return limitedResults;
            
        } catch (Exception e) {
            applicationMetrics.recordRagRetrieval(
                System.nanoTime() - startNanos,
                ApplicationMetrics.RetrievalPath.PRIMARY,
                0,
                ApplicationMetrics.Outcome.FAILURE
            );
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        long startNanos = System.nanoTime();
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                applicationMetrics.recordRagRetrieval(
                    System.nanoTime() - startNanos,
                    ApplicationMetrics.RetrievalPath.FALLBACK,
                    0,
                    ApplicationMetrics.Outcome.SUCCESS
                );
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            applicationMetrics.recordRagRetrieval(
                System.nanoTime() - startNanos,
                ApplicationMetrics.RetrievalPath.FALLBACK,
                results.size(),
                ApplicationMetrics.Outcome.SUCCESS
            );
            return results;
        } catch (Exception e) {
            applicationMetrics.recordRagRetrieval(
                System.nanoTime() - startNanos,
                ApplicationMetrics.RetrievalPath.FALLBACK,
                0,
                ApplicationMetrics.Outcome.FAILURE
            );
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

  private boolean activateVectorJob(Long knowledgeBaseId, String jobId, String generation, int chunkCount,
      VectorTaskExecutionDTO execution, BooleanSupplier permit) {
    Supplier<Boolean> action = () -> {
      if (!vectorRepository.lockExistingKnowledgeBase(knowledgeBaseId)) {
        vectorRepository.deleteByVectorJobId(jobId);
        return false;
      }
      var current = vectorRepository.findVectorTaskState(knowledgeBaseId);
      if (current.isEmpty() || !generation.equals(current.get().generation())
          || current.get().status() == VectorStatus.COMPLETED) {
        vectorRepository.deleteByVectorJobId(jobId);
        return false;
      }
      var task = durableTasks == null ? Optional.<VectorTaskEntity>empty()
          : durableTasks.lockExecution(generation);
      if (task.isPresent() && (execution == null
          || !KnowledgeBaseVectorTaskService.owns(task.get(), execution, durableTasks.databaseNow()))) {
        vectorRepository.deleteByVectorJobId(jobId);
        return false;
      }
      if (execution != null && task.isEmpty()) {
        vectorRepository.deleteByVectorJobId(jobId);
        return false;
      }
      if (!permit.getAsBoolean()) {
        vectorRepository.deleteByVectorJobId(jobId);
        return false;
      }
      vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
      int promoted = vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
      if (promoted != chunkCount || vectorRepository.completeVectorTask(knowledgeBaseId, generation, promoted) != 1) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量提升与完成状态未完整写入");
      }
      if (execution != null && durableTasks.completeExecution(knowledgeBaseId, generation,
          execution.owner(), execution.fence(), durableTasks.databaseNow()) != 1) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "持久化任务完成状态未提交");
      }
      if (execution != null) {
        vectorRepository.deleteSupersededPendingVectors(knowledgeBaseId, jobId);
      }
      return true;
    };
    return transactionalExecutor == null ? action.get() : transactionalExecutor.call(action);
  }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
