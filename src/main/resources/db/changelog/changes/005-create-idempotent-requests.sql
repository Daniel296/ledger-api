--liquibase formatted sql

--changeset ledger:create-idempotent-requests
CREATE TABLE idempotent_requests (
    idempotency_key VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL PRIMARY KEY,
    request_hash    VARCHAR(64)                                                NULL,
    response        MEDIUMTEXT                                                 NULL
);
