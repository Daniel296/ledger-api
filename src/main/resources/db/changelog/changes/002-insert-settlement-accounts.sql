--liquibase formatted sql

--changeset ledger:insert-settlement-accounts
-- The ledger's own accounts, with no overdraft limit
INSERT INTO accounts (id, currency, overdraft_limit, unlimited_overdraft, balance, created_at) VALUES
    ('ffd6f25c-cbbb-43ae-afe5-1834678bb9aa', 'EUR', 0, TRUE, 0, UTC_TIMESTAMP(6)),
    ('91509bf3-2df9-43f8-89e3-2283c7f5a97d', 'USD', 0, TRUE, 0, UTC_TIMESTAMP(6)),
    ('7bba09b3-74f5-4fbd-9315-25c9c98e4f94', 'JPY', 0, TRUE, 0, UTC_TIMESTAMP(6));
