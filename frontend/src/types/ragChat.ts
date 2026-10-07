import type { CitationValidationReport } from './ragCitation';

export type RagGenerationState = 'GENERATING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type RagDisplayState = RagGenerationState | 'INTERRUPTED' | 'LEGACY_INCOMPLETE';

export interface RagStreamEvent {
  event: 'start' | 'delta' | 'terminal' | 'heartbeat';
  messageId: number;
  content?: string | null;
  generationState?: RagGenerationState | null;
  errorCode?: string | null;
  message?: string | null;
}

export interface RagStreamCallbacks {
  signal: AbortSignal;
  onStart: (messageId: number) => void;
  onDelta: (chunk: string) => void;
  onTerminal: (event: RagStreamEvent) => void;
  onError: (error: Error) => void;
}

export interface RagChatSession {
  id: number;
  title: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}

export interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;
}

export interface RagChatMessage {
  id: number;
  type: 'user' | 'assistant';
  content: string;
  createdAt: string;
  evidence: RagChatEvidence[];
  citationValidation?: CitationValidationReport | null;
  completed?: boolean | null;
  generationState?: RagGenerationState | null;
  generationErrorCode?: string | null;
}

export interface RagChatEvidence {
  evidenceId?: string | null;
  knowledgeBaseId: number | null;
  documentSha256: string | null;
  chunkIndex: number | null;
  finalRank: number | null;
  retrievalSources: string[];
  contentPreview: string | null;
  originalFilename: string | null;
  contentType: string | null;
}

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
}

export interface RagChatSessionDetail {
  id: number;
  title: string;
  knowledgeBases: KnowledgeBaseItem[];
  messages: RagChatMessage[];
  createdAt: string;
  updatedAt: string;
}
