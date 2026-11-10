--liquibase formatted sql

--changeset ledger:create-outbox
CREATE TABLE outbox (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_id CHAR(36)    NOT NULL,
    payload        MEDIUMTEXT  NOT NULL,
    published_at   DATETIME(6) NULL,
    INDEX ix_outbox_published_at (published_at)
);

--changeset ledger:create-outbox-publisher
-- One row: only the instance holding its lock publishes the outbox
CREATE TABLE outbox_publisher (
    id INT NOT NULL PRIMARY KEY
);

INSERT INTO outbox_publisher (id) VALUES (1);
