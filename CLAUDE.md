# Instructions for the AI assistant

## Project

A double-entry ledger service (coding challenge). Stack: Java 17, Spring Boot 4, MySQL, Kafka, Maven.
Everything must run in Docker; one script builds the service, starts it and runs the tests.

## Domain rules that must never be broken

- A transaction has at least two entries; its debits and credits add up to the same total.
- All accounts of a transaction exist and share one currency (EUR, USD, JPY; 2, 2 and 0 decimals).
- No account goes past its overdraft limit. A transaction is recorded all or nothing.
- Transactions and entries are never updated or deleted; a mistake is corrected by a reversal,
  which can happen only once and cannot itself be reversed.
- Money is exact: `BigDecimal` in code, `DECIMAL` in MySQL, never `double` or `float`.

## Code

- Layering: controller → service → repository. Controllers stay thin; business rules live in
  services; SQL lives only in repositories. Transactions are owned by the service layer.
- Constructor injection only, depending on abstractions.
- No Javadoc or doc blocks. Comment only a non-obvious "why", in one short line.
- Never query inside a loop; never re-read data already loaded.

## Tests

- Guarantees (concurrency, idempotency, redelivery, integrity, refused transactions leaving nothing
  behind) are proven against the real MySQL and Kafka via Testcontainers, not mocks.
- Every behaviour change comes with its test in the same change.

## Workflow

- Work is split into merge requests of fewer than 20 files, except the project skeleton and the
  Docker / test infrastructure.
- The API contract (OpenAPI) is written before the code and kept in step with it.
- Commit messages are short, in the imperative mood, and carry no AI attribution.
