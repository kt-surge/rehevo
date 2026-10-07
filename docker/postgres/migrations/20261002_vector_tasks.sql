BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
CREATE TABLE IF NOT EXISTS kb_vector_tasks (
  generation varchar(36) PRIMARY KEY,
  kb_id bigint NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
  file_sha256 varchar(64) NOT NULL,
  content_sha256 varchar(64) NOT NULL,
  parsed_content text NOT NULL,
  chunking_mode varchar(20) NOT NULL,
  max_tokens integer NOT NULL,
  state varchar(20) NOT NULL,
  delivery_owner varchar(36),
  delivery_fence bigint NOT NULL DEFAULT 0,
  delivery_lease_until timestamptz,
  delivery_attempts integer NOT NULL DEFAULT 0,
  next_delivery_at timestamptz NOT NULL,
  last_message_id varchar(80),
  last_delivery_error varchar(500),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  completed_at timestamptz,
  CONSTRAINT ck_vector_task_state CHECK (state IN ('ACTIVE','COMPLETED','FAILED','OBSOLETE')),
  CONSTRAINT ck_vector_task_chunking CHECK (chunking_mode IN ('TOKEN','STRUCTURED') AND max_tokens BETWEEN 64 AND 2048)
);
CREATE INDEX IF NOT EXISTS idx_vector_task_delivery ON kb_vector_tasks(next_delivery_at,created_at,generation)
  WHERE state='ACTIVE';
CREATE INDEX IF NOT EXISTS idx_vector_task_kb ON kb_vector_tasks(kb_id);
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases k WHERE k.vector_status IN ('PENDING','PROCESSING')
      AND NOT EXISTS (SELECT 1 FROM kb_vector_tasks t WHERE t.kb_id=k.id AND t.generation=k.vector_generation)) THEN
    RAISE EXCEPTION 'Unfinished requests lack durable snapshots; stop writers and restore the reviewed recovery list first';
  END IF;
END $$;
COMMIT;
