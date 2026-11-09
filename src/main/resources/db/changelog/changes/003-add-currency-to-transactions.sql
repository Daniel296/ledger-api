--liquibase formatted sql

--changeset ledger:add-currency-to-transactions
ALTER TABLE transactions ADD COLUMN currency VARCHAR(3) NOT NULL;
