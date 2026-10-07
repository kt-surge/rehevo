BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
LOCK TABLE kb_vector_tasks IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases WHERE vector_status IN ('PENDING','PROCESSING'))
      OR EXISTS (SELECT 1 FROM kb_vector_tasks WHERE state='ACTIVE') THEN
    RAISE EXCEPTION 'Stop all writers/workers and finish or explicitly recover active tasks before execution migration';
  END IF;
END $$;
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS execution_owner varchar(36);
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS execution_fence bigint NOT NULL DEFAULT 0;
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS execution_lease_until timestamptz;
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS execution_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS max_execution_attempts integer NOT NULL DEFAULT 4;
ALTER TABLE kb_vector_tasks ADD COLUMN IF NOT EXISTS last_execution_error varchar(500);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='kb_vector_tasks'::regclass
      AND conname='ck_vector_execution_budget') THEN
    ALTER TABLE kb_vector_tasks ADD CONSTRAINT ck_vector_execution_budget
      CHECK (execution_attempts>=0 AND max_execution_attempts BETWEEN 1 AND 4
        AND execution_attempts<=max_execution_attempts AND execution_fence>=0);
  END IF;
END $$;
COMMIT;
