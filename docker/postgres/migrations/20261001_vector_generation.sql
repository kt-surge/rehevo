-- Stop old writers/consumers and drain unversioned unfinished jobs before applying.
-- Explicit manual migration; the application does not depend on ddl-auto in production.
BEGIN;
LOCK TABLE knowledge_bases IN ACCESS EXCLUSIVE MODE;
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS vector_generation varchar(36);
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM knowledge_bases
      WHERE vector_generation IS NULL AND vector_status IN ('PENDING', 'PROCESSING')) THEN
    RAISE EXCEPTION 'Unfinished legacy vector jobs require a migration inventory; stop and reconcile before applying';
  END IF;
END $$;
COMMIT;
