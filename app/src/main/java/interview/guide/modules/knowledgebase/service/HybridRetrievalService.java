package interview.guide.modules.knowledgebase.service;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {
  private final KnowledgeBaseVectorService vectorService;
  private final VectorRepository vectorRepository;
  private final QwenRerankService rerankService;
  private final KnowledgeBaseQueryProperties properties;
  private final ApplicationMetrics applicationMetrics;
  private final JTokkitTokenCountEstimator tokenEstimator = new JTokkitTokenCountEstimator();

  public List<Document> retrieve(String query, String originalQuestion, List<Long> knowledgeBaseIds,
                                 int topK, double minScore, RetrievalMode mode) {
    return retrieveWithTrace(query, originalQuestion, knowledgeBaseIds, topK, minScore, mode).documents();
  }

  /** 单次检索采集裁剪前后结果，不额外调用 Embedding 或重排模型。 */
  public RetrievalTrace retrieveWithTrace(String query, String originalQuestion,
                                           List<Long> knowledgeBaseIds, int topK,
                                           double minScore, RetrievalMode mode) {
    return retrieveWithTrace(query, originalQuestion, knowledgeBaseIds, topK, minScore, mode, null);
  }

  public RetrievalTrace retrieveWithTrace(String query, String originalQuestion,
                                           List<Long> knowledgeBaseIds, int topK,
                                           double minScore, RetrievalMode mode,
                                           Integer contextTokenBudget) {
    if (mode == RetrievalMode.HYBRID_FOCUSED) {
      return retrieveFocused(query, originalQuestion, knowledgeBaseIds, topK, minScore,
          contextTokenBudget);
    }
    if (mode == RetrievalMode.HYBRID_IDENTIFIER) {
      RetrievalTrace original = retrieveWithTrace(query, originalQuestion, knowledgeBaseIds,
          topK, minScore, RetrievalMode.HYBRID);
      int budget = contextTokenBudget == null ? countTokens(original.documents()) : contextTokenBudget;
      var selection = IdentifierEvidenceSelector.select(originalQuestion, original.candidates(),
          topK, budget, tokenEstimator::estimate);
      return new RetrievalTrace(original.vectorDocuments(), original.lexicalDocuments(),
          original.candidates(), addFinalRanks(selection.documents()), List.of(), 1, budget,
          selection.tokenEstimate(), selection.reservations());
    }
    int vectorLimit = mode == RetrievalMode.VECTOR
        ? topK : Math.max(topK, properties.getHybrid().getVectorCandidates());
    long vectorStart = System.nanoTime();
    List<Document> vectorDocuments = vectorService.similaritySearch(
        query, knowledgeBaseIds, vectorLimit, minScore);
    applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.VECTOR,
        System.nanoTime() - vectorStart, vectorDocuments.size(), ApplicationMetrics.Outcome.SUCCESS);
    if (mode == RetrievalMode.VECTOR) {
      List<Document> candidates = addVectorRanks(vectorDocuments);
      List<Document> result = selectWithinBudget(candidates, topK, contextTokenBudget);
      return new RetrievalTrace(candidates, List.of(), candidates, addFinalRanks(result),
          List.of(), 1, contextTokenBudget,
          contextTokenBudget == null ? null : countTokens(result));
    }

    List<Document> lexicalDocuments = lexicalSearch(query, knowledgeBaseIds);
    long fusionStart = System.nanoTime();
    List<Document> fused = reciprocalRankFusion(vectorDocuments, lexicalDocuments,
        properties.getHybrid().getRrfK(), properties.getHybrid().getVectorWeight(),
        properties.getHybrid().getLexicalWeight(), properties.getHybrid().getFusionCandidates());
    applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.FUSION,
        System.nanoTime() - fusionStart, fused.size(), ApplicationMetrics.Outcome.SUCCESS);
    List<Document> result = mode == RetrievalMode.HYBRID_RERANK
        ? rerankService.rerank(originalQuestion, fused, topK)
        : fused.stream().limit(topK).toList();
    if (mode == RetrievalMode.HYBRID_CONTEXT) {
      result = expandAdjacentChunks(result, topK);
    }
    if (contextTokenBudget != null) {
      List<Document> budgetCandidates = mode == RetrievalMode.HYBRID ? fused : result;
      result = selectWithinBudget(budgetCandidates, topK, contextTokenBudget);
    }
    return new RetrievalTrace(addVectorRanks(vectorDocuments), lexicalDocuments,
        fused, addFinalRanks(result), List.of(), 1, contextTokenBudget,
        contextTokenBudget == null ? null : countTokens(result));
  }

  public record RetrievalTrace(List<Document> vectorDocuments, List<Document> lexicalDocuments,
                                List<Document> candidates, List<Document> documents,
                                List<FocusedQueryTrace> focusedQueries, int vectorSearchCalls,
                                Integer contextTokenBudget, Integer contextTokenEstimate,
                                List<IdentifierEvidenceSelector.Reservation> identifierReservations) {
    public RetrievalTrace(List<Document> vectorDocuments, List<Document> lexicalDocuments,
                          List<Document> candidates, List<Document> documents,
                          List<FocusedQueryTrace> focusedQueries, int vectorSearchCalls,
                          Integer contextTokenBudget, Integer contextTokenEstimate) {
      this(vectorDocuments, lexicalDocuments, candidates, documents, focusedQueries,
          vectorSearchCalls, contextTokenBudget, contextTokenEstimate, List.of());
    }

    public RetrievalTrace(List<Document> vectorDocuments, List<Document> lexicalDocuments,
                          List<Document> candidates, List<Document> documents) {
      this(vectorDocuments, lexicalDocuments, candidates, documents, List.of(), 0, null, null);
    }

    public RetrievalTrace {
      vectorDocuments = List.copyOf(vectorDocuments);
      lexicalDocuments = List.copyOf(lexicalDocuments);
      candidates = List.copyOf(candidates);
      documents = List.copyOf(documents);
      focusedQueries = List.copyOf(focusedQueries);
      identifierReservations = List.copyOf(identifierReservations);
    }
  }

  public record FocusedQueryTrace(String query, List<Document> vectorDocuments,
                                  List<Document> lexicalDocuments, List<Document> candidates) {
    public FocusedQueryTrace {
      vectorDocuments = List.copyOf(vectorDocuments);
      lexicalDocuments = List.copyOf(lexicalDocuments);
      candidates = List.copyOf(candidates);
    }
  }

  private RetrievalTrace retrieveFocused(String query, String originalQuestion,
                                          List<Long> knowledgeBaseIds, int topK,
                                          double minScore, Integer requestedBudget) {
    RetrievalTrace original = retrieveWithTrace(query, originalQuestion, knowledgeBaseIds,
        topK, minScore, RetrievalMode.HYBRID);
    int budget = requestedBudget == null ? countTokens(original.documents()) : requestedBudget;
    List<String> planned = FocusedRetrievalPlanner.plan(originalQuestion).stream()
        .filter(focus -> !focus.equals(query)).toList();
    List<FocusedQueryTrace> focused = new ArrayList<>();
    List<List<Document>> rankings = new ArrayList<>();
    rankings.add(original.candidates());
    for (String focus : planned) {
      RetrievalTrace trace = retrieveWithTrace(focus, originalQuestion, knowledgeBaseIds,
          topK, minScore, RetrievalMode.HYBRID);
      focused.add(new FocusedQueryTrace(focus, trace.vectorDocuments(),
          trace.lexicalDocuments(), trace.candidates()));
      rankings.add(trace.candidates());
    }
    List<Document> candidates = interleaveRankings(rankings, properties.getHybrid().getFusionCandidates());
    List<Document> result = selectWithinBudget(candidates, topK, budget);
    return new RetrievalTrace(original.vectorDocuments(), original.lexicalDocuments(),
        candidates, addFinalRanks(result), focused, 1 + focused.size(), budget, countTokens(result));
  }

  static List<Document> interleaveRankings(List<List<Document>> rankings, int limit) {
    List<Document> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int max = rankings.stream().mapToInt(List::size).max().orElse(0);
    for (int rank = 0; rank < max && result.size() < limit; rank++) {
      for (List<Document> ranking : rankings) {
        if (rank < ranking.size() && result.size() < limit) {
          Document document = ranking.get(rank);
          if (seen.add(document.getId())) {
            result.add(document);
          }
        }
      }
    }
    return result;
  }

  private List<Document> selectWithinBudget(List<Document> candidates, int topK, Integer budget) {
    if (budget == null) {
      return candidates.stream().limit(topK).toList();
    }
    List<Document> selected = new ArrayList<>();
    int used = 0;
    for (Document document : candidates) {
      if (selected.size() >= topK) {
        break;
      }
      int tokens = tokenEstimator.estimate(document.getText());
      if (budget == null || used + tokens <= budget) {
        selected.add(document);
        used += tokens;
      }
    }
    return selected;
  }

  private int countTokens(List<Document> documents) {
    return documents.stream().mapToInt(document -> tokenEstimator.estimate(document.getText())).sum();
  }

  private List<Document> expandAdjacentChunks(List<Document> rankedDocuments, int topK) {
    KnowledgeBaseQueryProperties.ContextExpansion config = properties.getContextExpansion();
    int seedLimit = Math.max(0, config.getSeedChunks());
    int neighborsPerSeed = Math.max(0, config.getNeighborsPerSeed());
    if (rankedDocuments.isEmpty() || seedLimit == 0 || neighborsPerSeed == 0) {
      return rankedDocuments;
    }

    Map<Long, Set<Integer>> requestedIndexes = new LinkedHashMap<>();
    int seeds = 0;
    for (Document document : rankedDocuments) {
      if (seeds >= seedLimit) {
        break;
      }
      ChunkLocation location = chunkLocation(document);
      if (location == null) {
        continue;
      }
      Set<Integer> indexes = requestedIndexes.computeIfAbsent(location.knowledgeBaseId(), ignored -> new TreeSet<>());
      if (location.chunkIndex() > 0) {
        indexes.add(location.chunkIndex() - 1);
      }
      indexes.add(location.chunkIndex() + 1);
      seeds++;
    }
    if (requestedIndexes.isEmpty()) {
      return rankedDocuments;
    }

    long startNanos = System.nanoTime();
    try {
      List<Document> adjacentDocuments = vectorRepository.findChunksByKnowledgeBaseAndIndexes(requestedIndexes);
      List<Document> expanded = interleaveAdjacentChunks(
          rankedDocuments, adjacentDocuments, seedLimit, neighborsPerSeed, topK);
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.CONTEXT_EXPANSION,
          System.nanoTime() - startNanos, expanded.size(), ApplicationMetrics.Outcome.SUCCESS);
      return expanded;
    } catch (Exception e) {
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.CONTEXT_EXPANSION,
          System.nanoTime() - startNanos, rankedDocuments.size(), ApplicationMetrics.Outcome.FAILURE);
      log.warn("相邻 Chunk 补全失败，本次保留原始检索结果: {}", e.getMessage());
      return rankedDocuments;
    }
  }

  static List<Document> interleaveAdjacentChunks(List<Document> rankedDocuments,
                                                   List<Document> adjacentDocuments,
                                                   int seedLimit, int neighborsPerSeed, int topK) {
    Map<String, Document> adjacentByLocation = new HashMap<>();
    for (Document document : adjacentDocuments) {
      ChunkLocation location = chunkLocation(document);
      if (location != null) {
        adjacentByLocation.put(location.key(), document);
      }
    }
    List<Document> result = new ArrayList<>();
    Set<String> includedIds = new java.util.HashSet<>();
    int seedCount = 0;
    for (Document document : rankedDocuments) {
      if (result.size() >= topK) {
        break;
      }
      addIfAbsent(result, includedIds, document);
      ChunkLocation location = chunkLocation(document);
      if (location == null || seedCount >= seedLimit) {
        continue;
      }
      seedCount++;
      int addedNeighbors = 0;
      for (int neighborIndex : List.of(location.chunkIndex() - 1, location.chunkIndex() + 1)) {
        if (neighborIndex < 0 || addedNeighbors >= neighborsPerSeed || result.size() >= topK) {
          continue;
        }
        Document neighbor = adjacentByLocation.get(location.knowledgeBaseId() + ":" + neighborIndex);
        if (neighbor != null && addIfAbsent(result, includedIds, withAdjacentMetadata(neighbor, document.getId()))) {
          addedNeighbors++;
        }
      }
    }
    return result;
  }

  private static boolean addIfAbsent(List<Document> documents, Set<String> includedIds, Document document) {
    if (document == null || document.getId() == null || !includedIds.add(document.getId())) {
      return false;
    }
    documents.add(document);
    return true;
  }

  private static Document withAdjacentMetadata(Document document, String seedDocumentId) {
    Map<String, Object> metadata = new HashMap<>(document.getMetadata());
    List<String> sources = new ArrayList<>();
    Object value = metadata.get("retrieval_sources");
    if (value instanceof List<?> list) {
      list.stream().map(String::valueOf).forEach(sources::add);
    }
    if (!sources.contains("adjacent_context")) {
      sources.add("adjacent_context");
    }
    metadata.put("retrieval_sources", sources);
    metadata.put("retrieval_adjacent_to", seedDocumentId);
    return Document.builder().id(document.getId()).text(document.getText())
        .metadata(metadata).score(document.getScore()).build();
  }

  private static ChunkLocation chunkLocation(Document document) {
    Object knowledgeBaseId = document.getMetadata().get("kb_id");
    Object chunkIndex = document.getMetadata().get("chunk_index");
    if (knowledgeBaseId == null || chunkIndex == null) {
      return null;
    }
    try {
      return new ChunkLocation(Long.parseLong(knowledgeBaseId.toString()), Integer.parseInt(chunkIndex.toString()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private record ChunkLocation(Long knowledgeBaseId, int chunkIndex) {
    private String key() {
      return knowledgeBaseId + ":" + chunkIndex;
    }
  }

  private List<Document> lexicalSearch(String query, List<Long> knowledgeBaseIds) {
    long startNanos = System.nanoTime();
    try {
      List<Document> documents = vectorRepository.lexicalSearch(
          query, knowledgeBaseIds, properties.getHybrid().getLexicalCandidates());
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.LEXICAL,
          System.nanoTime() - startNanos, documents.size(), ApplicationMetrics.Outcome.SUCCESS);
      return documents;
    } catch (Exception e) {
      applicationMetrics.recordRagRetrievalStage(ApplicationMetrics.RetrievalStage.LEXICAL,
          System.nanoTime() - startNanos, 0, ApplicationMetrics.Outcome.FAILURE);
      log.warn("字符三元组检索失败，本次仅使用向量候选: {}", e.getMessage());
      return List.of();
    }
  }

  static List<Document> reciprocalRankFusion(List<Document> vectorDocuments,
                                              List<Document> lexicalDocuments,
                                              int rrfK, double vectorWeight,
                                              double lexicalWeight, int limit) {
    Map<String, Candidate> candidates = new LinkedHashMap<>();
    addRanking(candidates, vectorDocuments, true, rrfK, vectorWeight);
    addRanking(candidates, lexicalDocuments, false, rrfK, lexicalWeight);
    return candidates.values().stream()
        .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
            .thenComparing(candidate -> candidate.document().getId()))
        .limit(limit)
        .map(Candidate::toDocument)
        .toList();
  }

  private static void addRanking(Map<String, Candidate> candidates, List<Document> documents,
                                 boolean vector, int rrfK, double weight) {
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      int rank = index + 1;
      Candidate candidate = candidates.computeIfAbsent(document.getId(), id -> new Candidate(document));
      candidate.addRank(vector, rank, weight / (rrfK + rank));
    }
  }

  private List<Document> addFinalRanks(List<Document> documents) {
    List<Document> ranked = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      var metadata = new HashMap<>(document.getMetadata());
      metadata.put("retrieval_final_rank", index + 1);
      ranked.add(Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(document.getScore()).build());
    }
    return ranked;
  }

  private List<Document> addVectorRanks(List<Document> documents) {
    List<Document> ranked = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      var metadata = new HashMap<>(document.getMetadata());
      metadata.put("retrieval_vector_rank", index + 1);
      metadata.put("retrieval_vector_score", document.getScore());
      metadata.put("retrieval_sources", List.of("vector"));
      ranked.add(Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(document.getScore()).build());
    }
    return ranked;
  }

  private static final class Candidate {
    private Document document;
    private Integer vectorRank;
    private Integer lexicalRank;
    private Double vectorScore;
    private double rrfScore;

    private Candidate(Document document) {
      this.document = document;
    }

    private void addRank(boolean vector, int rank, double contribution) {
      if (vector) {
        vectorRank = rank;
        vectorScore = document.getScore();
      } else {
        lexicalRank = rank;
      }
      rrfScore += contribution;
    }

    private double rrfScore() {
      return rrfScore;
    }

    private Document document() {
      return document;
    }

    private Document toDocument() {
      var metadata = new HashMap<>(document.getMetadata());
      if (vectorRank != null) {
        metadata.put("retrieval_vector_rank", vectorRank);
        metadata.put("retrieval_vector_score", vectorScore);
      }
      if (lexicalRank != null) {
        metadata.put("retrieval_lexical_rank", lexicalRank);
      }
      metadata.put("retrieval_rrf_score", rrfScore);
      List<String> sources = new ArrayList<>();
      if (vectorRank != null) {
        sources.add("vector");
      }
      if (lexicalRank != null) {
        sources.add("lexical");
      }
      metadata.put("retrieval_sources", sources);
      return Document.builder().id(document.getId()).text(document.getText())
          .metadata(metadata).score(rrfScore).build();
    }
  }
}
