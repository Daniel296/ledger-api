--liquibase formatted sql

--changeset ledger:add-reversal-of-to-transactions
ALTER TABLE transactions
    ADD COLUMN reversal_of CHAR(36) NULL,
    ADD CONSTRAINT uq_transactions_reversal_of UNIQUE (reversal_of),
    ADD CONSTRAINT fk_transactions_reversal_of FOREIGN KEY (reversal_of) REFERENCES transactions (id);
