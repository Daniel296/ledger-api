--liquibase formatted sql

--changeset ledger:create-accounts
CREATE TABLE accounts (
    id                  CHAR(36)       NOT NULL PRIMARY KEY,
    currency            VARCHAR(3)     NOT NULL,
    overdraft_limit     DECIMAL(19, 4) NOT NULL,
    unlimited_overdraft BOOLEAN        NOT NULL,
    balance             DECIMAL(19, 4) NOT NULL,
    created_at          DATETIME(6)    NOT NULL
);

--changeset ledger:create-transactions
CREATE TABLE transactions (
    id          CHAR(36)     NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    recorded_at DATETIME(6)  NOT NULL
);

--changeset ledger:create-entries
CREATE TABLE entries (
    id             BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_id CHAR(36)       NOT NULL,
    account_id     CHAR(36)       NOT NULL,
    direction      VARCHAR(6)     NOT NULL,
    amount         DECIMAL(19, 4) NOT NULL,
    balance_after  DECIMAL(19, 4) NOT NULL,
    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id)
);
