--liquibase formatted sql

--changeset ledger:add-correlation-id-to-outbox
ALTER TABLE outbox ADD COLUMN correlation_id VARCHAR(200) NULL;
