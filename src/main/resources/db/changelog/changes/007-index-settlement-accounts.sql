--liquibase formatted sql

--changeset ledger:index-settlement-accounts
-- A currency's settlement account is found by these two columns, for every settled payment
CREATE INDEX ix_accounts_settlement ON accounts (unlimited_overdraft, currency);
