--liquibase formatted sql

--changeset ledger:insert-settlement-accounts
-- The ledger's own accounts, with no overdraft limit
INSERT INTO accounts (id, currency, overdraft_limit, unlimited_overdraft, balance, created_at) VALUES
    (UUID(), 'EUR', 0, TRUE, 0, UTC_TIMESTAMP(6)),
    (UUID(), 'USD', 0, TRUE, 0, UTC_TIMESTAMP(6)),
    (UUID(), 'JPY', 0, TRUE, 0, UTC_TIMESTAMP(6));
