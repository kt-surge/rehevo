-- Stop writers, consumers and all recovery/heartbeat schedulers; export task history and verify Redis separately.
BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
LOCK TABLE kb_vector_tasks IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases WHERE vector_status IN ('PENDING','PROCESSING'))
      OR EXISTS (SELECT 1 FROM kb_vector_tasks WHERE state='ACTIVE') THEN
    RAISE EXCEPTION 'Unfinished requests prevent removal of execution ownership and retry history';
  END IF;
END $$;
ALTER TABLE kb_vector_tasks DROP CONSTRAINT IF EXISTS ck_vector_execution_budget;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS execution_owner;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS execution_fence;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS execution_lease_until;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS execution_attempts;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS max_execution_attempts;
ALTER TABLE kb_vector_tasks DROP COLUMN IF EXISTS last_execution_error;
COMMIT;
