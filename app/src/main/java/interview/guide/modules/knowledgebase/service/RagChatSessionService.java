package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.MessageDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.RetrievalEvidenceDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagGenerationState;
import interview.guide.modules.knowledgebase.model.RagStreamEventDTO;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        // 验证知识库存在
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(request.knowledgeBaseIds());

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return ragChatMapper.toSessionDTO(session);
    }

    /**
     * 获取会话列表
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库
        RagChatSessionEntity session = sessionRepository
            .findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 转换知识库列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        List<MessageDTO> messageDTOs = messages.stream()
            .map(this::toMessageDTO)
            .toList();

        return new SessionDetailDTO(
            session.getId(), session.getTitle(), kbDTOs, messageDTOs,
            session.getCreatedAt(), session.getUpdatedAt()
        );
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdForUpdate(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage.setGenerationState(RagGenerationState.GENERATING);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后更新消息
     */
    @Transactional
    public RagGenerationState finishStreamMessage(Long messageId, String content,
            List<QueryResponse.RetrievalEvidence> evidence, RagGenerationState state, String errorCode) {
        if (state == null || state == RagGenerationState.GENERATING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "终止状态无效");
        }
        var found = messageRepository.findByIdForUpdate(messageId);
        if (found.isEmpty()) {
            return null;
        }
        RagChatMessageEntity message = found.get();
        if (message.getGenerationState() != RagGenerationState.GENERATING) {
            return message.getGenerationState();
        }
        message.setContent(content);
        message.setEvidenceJson(writeEvidence(evidence));
        message.setGenerationState(state);
        message.setGenerationErrorCode(errorCode);
        message.setCompleted(state == RagGenerationState.COMPLETED);
        messageRepository.saveAndFlush(message);
        log.info("保存 RAG 终止状态: messageId={}, state={}, contentLength={}", messageId, state, content.length());
        return state;
    }

    /**
     * 获取流式回答（带多轮上下文）
     */
    public KnowledgeBaseQueryService.StreamAnswer getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
            ? loadHistoryMessages(sessionId) : List.of();

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStreamWithEvidence(kbIds, question, history);
    }

    public RagStreamEventDTO getStreamTerminal(Long sessionId, Long messageId) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .filter(item -> sessionId.equals(item.getSession().getId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));
        if (message.getGenerationState() == RagGenerationState.GENERATING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成资源不在当前实例，请在原连接所属实例停止");
        }
        return RagStreamEventDTO.terminal(messageId, message.getGenerationState(),
            message.getGenerationErrorCode(), "回答已经结束");
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);

        log.info("删除会话: sessionId={}", sessionId);
    }

    // ========== 私有方法 ==========

    private MessageDTO toMessageDTO(RagChatMessageEntity message) {
        List<RetrievalEvidenceDTO> evidence = readEvidence(message.getEvidenceJson());
        return new MessageDTO(message.getId(), message.getTypeString(), message.getContent(),
            message.getCreatedAt(), evidence, RagCitationValidator.check(message.getContent(),
                evidence.stream().map(RetrievalEvidenceDTO::evidenceId).toList()), message.getCompleted(),
            message.getGenerationState(), message.getGenerationErrorCode());
    }

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     * Repository 只取完整成功问答对，当前问题及失败/取消问题均不会加入。
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() / 2 * 2;
        if (limit <= 0) {
            return List.of();
        }
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 反转为正序（时间从早到晚）
        return recent.reversed().stream()
            .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                ? (Message) new UserMessage(m.getContent())
                : (Message) new AssistantMessage(m.getContent()))
            .toList();
    }

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }

    private String writeEvidence(
            List<QueryResponse.RetrievalEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        List<RetrievalEvidenceDTO> snapshots = evidence.stream()
            .map(item -> new RetrievalEvidenceDTO(
                item.knowledgeBaseId(), item.documentSha256(), item.chunkIndex(), item.finalRank(),
                item.retrievalSources(), item.contentPreview(), item.originalFilename(), item.contentType(),
                item.evidenceId()
            ))
            .toList();
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "回答来源保存失败", e);
        }
    }

    private List<RetrievalEvidenceDTO> readEvidence(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(evidenceJson, new TypeReference<List<RetrievalEvidenceDTO>>() {});
        } catch (Exception e) {
            log.warn("无法读取 RAG 证据快照: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
