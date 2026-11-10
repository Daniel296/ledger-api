--liquibase formatted sql

--changeset ledger:add-refusal-to-idempotent-requests
ALTER TABLE idempotent_requests ADD COLUMN refusal MEDIUMTEXT NULL;
