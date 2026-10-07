package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagGenerationState;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagChatDTO.RetrievalEvidenceDTO;
import interview.guide.modules.knowledgebase.model.CitationValidationReport.Status;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 聊天会话证据快照")
class RagChatSessionServiceTest {

    @Test
    @DisplayName("流式回答完成时保存本轮检索证据快照")
    void savesEvidenceSnapshotWithAssistantMessage() {
        RagChatMessageRepository messageRepository = mock(RagChatMessageRepository.class);
        RagChatMessageEntity message = new RagChatMessageEntity();
        message.setContent("");
        message.setGenerationState(RagGenerationState.GENERATING);
        when(messageRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(message));

        RagChatSessionService service = new RagChatSessionService(
            mock(RagChatSessionRepository.class),
            messageRepository,
            mock(KnowledgeBaseRepository.class),
            mock(KnowledgeBaseQueryService.class),
            mock(RagChatMapper.class),
            mock(KnowledgeBaseMapper.class),
            new KnowledgeBaseQueryProperties(),
            new ObjectMapper()
        );
        List<QueryResponse.RetrievalEvidence> evidence = List.of(new QueryResponse.RetrievalEvidence(
            "vector-1", 3L, "sha-1", 2, 0.72, 1, null, 0.05,
            null, 1, List.of("vector"), "证据片段", "java-guide.md", "text/markdown", "E1"
        ));

        service.finishStreamMessage(7L, "回答内容", evidence, RagGenerationState.COMPLETED, null);

        assertThat(message.getContent()).isEqualTo("回答内容");
        assertThat(message.getEvidenceJson()).contains("sha-1").contains("证据片段")
            .contains("java-guide.md").contains("text/markdown");
        assertThat(message.getCompleted()).isTrue();
        verify(messageRepository).saveAndFlush(message);
    }

    @Test
    @DisplayName("证据编号随快照保存并重载，未知引用不删除回答")
    void reloadsExactSnapshotAndReportsUnknownReference() throws Exception {
        var repository = mock(RagChatSessionRepository.class);
        var messages = mock(RagChatMessageRepository.class);
        var mapper = mock(KnowledgeBaseMapper.class);
        var session = new RagChatSessionEntity();
        session.setKnowledgeBases(Set.of());
        when(repository.findByIdWithKnowledgeBases(8L)).thenReturn(Optional.of(session));
        when(mapper.toListItemDTOList(any())).thenReturn(List.of());
        var message = new RagChatMessageEntity();
        message.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        message.setGenerationState(RagGenerationState.GENERATING);
        when(messages.findByIdForUpdate(7L)).thenReturn(Optional.of(message));
        when(messages.findBySessionIdOrderByMessageOrderAsc(8L)).thenReturn(List.of(message));
        var service = new RagChatSessionService(repository, messages, mock(KnowledgeBaseRepository.class),
            mock(KnowledgeBaseQueryService.class), mock(RagChatMapper.class), mapper,
            new KnowledgeBaseQueryProperties(), new ObjectMapper());
        service.finishStreamMessage(7L, "事实[E1]，未知[E99]", List.of(new QueryResponse.RetrievalEvidence(
            "source", 3L, "sha", 6, null, null, null, null, null, 9,
            List.of("vector"), "原始证据", "source.md", "text/markdown", "E1")), RagGenerationState.COMPLETED, null);
        var reloaded = service.getSessionDetail(8L).messages().getFirst();
        assertThat(reloaded.evidence().getFirst().evidenceId()).isEqualTo("E1");
        assertThat(reloaded.evidence().getFirst().chunkIndex()).isEqualTo(6);
        assertThat(reloaded.content()).isEqualTo("事实[E1]，未知[E99]");
        assertThat(reloaded.citationValidation().status()).isEqualTo(Status.UNKNOWN_REFERENCES);
        assertThat(reloaded.citationValidation().unknownEvidenceIds()).containsExactly("E99");
    }

    @Test
    @DisplayName("旧版证据 JSON 缺编号时兼容读取，不能按历史名次补编号")
    void legacyJsonKeepsMissingIdentifier() throws Exception {
        var evidence = new ObjectMapper().readValue(
            "{\"knowledgeBaseId\":3,\"finalRank\":1,\"contentPreview\":\"旧片段\"}", RetrievalEvidenceDTO.class);
        assertThat(evidence.evidenceId()).isNull();
        assertThat(RagCitationValidator.check("历史[E1]", java.util.Arrays.asList(evidence.evidenceId())).status())
            .isEqualTo(Status.DISABLED);
    }
}
