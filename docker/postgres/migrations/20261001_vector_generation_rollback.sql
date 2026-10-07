-- Stop all writers/consumers; independently verify Redis PEL and group lag are both zero.
-- Roll back application code before resuming writers. This discards historical generation IDs.
BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases WHERE vector_status IN ('PENDING', 'PROCESSING')) THEN
    RAISE EXCEPTION 'Unfinished vector jobs prevent schema rollback';
  END IF;
END $$;
ALTER TABLE knowledge_bases DROP COLUMN IF EXISTS vector_generation;
COMMIT;
