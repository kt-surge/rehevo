-- Stop writers, recovery scheduler and consumers; independently verify Redis lag/PEL and save task inventory.
BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
LOCK TABLE kb_vector_tasks IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases WHERE vector_status IN ('PENDING','PROCESSING'))
      OR EXISTS (SELECT 1 FROM kb_vector_tasks WHERE state='ACTIVE') THEN
    RAISE EXCEPTION 'Unfinished durable tasks remain; rollback would discard recovery inputs';
  END IF;
END $$;
DROP TABLE kb_vector_tasks;
COMMIT;
