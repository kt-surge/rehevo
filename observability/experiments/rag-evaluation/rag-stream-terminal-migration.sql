-- PostgreSQL；部署前先执行，再升级应用。旧行保持 NULL，不推断旧模型结果。
ALTER TABLE rag_chat_messages ADD COLUMN IF NOT EXISTS generation_state varchar(20);
ALTER TABLE rag_chat_messages ADD COLUMN IF NOT EXISTS generation_error_code varchar(40);
