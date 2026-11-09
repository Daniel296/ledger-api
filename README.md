# Ledger API

A double-entry ledger service: money is held in accounts and moves only through balanced, immutable
transactions. Transactions are recorded through a REST API and from payment-settled messages, and
every recorded transaction is announced to other services through Kafka.

## Stack

Java 17, Spring Boot 4, Spring Data JPA (Hibernate), MySQL 8.4, Liquibase, Kafka, Maven (wrapper
included).

## Run

Requires Docker, Git and a JDK 17+ (on the `PATH` or in `JAVA_HOME`).

```bash
./run.sh
```

The script builds the service image, starts it with MySQL through Docker Compose, waits until it
is healthy, then runs the test suite. The tests start their own MySQL through Testcontainers, so
they never touch the running service's data.

The service listens on `http://localhost:8080`; `GET /actuator/health` reports its status.
Stop everything with `docker compose down` (add `-v` to also drop the database volume).

## API

The contract is written first and lives in [docs/openapi.yaml](docs/openapi.yaml).

| Method | Path | |
|---|---|---|
| `POST` | `/v1/accounts` | Open an account |
| `GET` | `/v1/accounts/{accountId}` | Read an account with its balance |
| `GET` | `/v1/accounts/{accountId}/entries` | An account's entries, newest first, paged, with the balance after each |
| `GET` | `/v1/accounts/{accountId}/balance?asOf=…` | An account's balance as at a past instant |
| `POST` | `/v1/transactions` | Record a transaction |
| `GET` | `/v1/transactions/{transactionId}` | Read a transaction with its entries |
| `POST` | `/v1/transactions/{transactionId}/reversals` | Reverse a transaction |

- **Amounts are decimal strings** (`"125.50"`), so no client reads them as floating point.
- **Every `POST` needs an `Idempotency-Key`**: a retry with the same key and body gets the original
  response and takes effect at most once; the same key with a different body is refused.
- **Errors** are `{"errorCode", "message"}`: a stable code a calling system can act on, and a
  message naming the value at fault. `400` malformed request, `404` unknown resource in the path, `409`
  conflicting state (already reversed, request still in progress), `422` a ledger rule is broken.
- **Versioned in the path**: `v1` only evolves additively, so clients must ignore unknown fields.
- **`X-Request-Id`** (optional, generated when missing) is returned on the response and carried into
  the logs and the announcements a request causes.
- **Entries are paged by cursor**, not offset, so entries recorded meanwhile never shift a page.

## Persistence decisions

- The schema is owned by Liquibase changelogs written as formatted SQL, so the constraints the
  ledger relies on are explicit and reviewable.
- Hibernate only validates the schema (`ddl-auto: validate`); it never creates or changes it.
- `open-in-view` is off: entities never leave a service transaction.
- Money is `DECIMAL(19, 4)`: exact, and room for 15 integer digits.
- Accounts and transactions have UUIDs: they are public identifiers, so they must not be guessable
  or reveal volumes. Entries are internal and keep an auto-increment id (which also means Hibernate
  inserts them one by one, without JDBC batching).
- Transactions and entries are `@Immutable`: Hibernate never issues an `UPDATE` for them.
- The ledger's own settlement accounts are created by the migration, with no overdraft limit and
  fixed random ids: EUR `ffd6f25c-cbbb-43ae-afe5-1834678bb9aa`, USD
  `91509bf3-2df9-43f8-89e3-2283c7f5a97d`, JPY `7bba09b3-74f5-4fbd-9315-25c9c98e4f94`. The PDF names
  only EUR and JPY; USD gets one too, since the ledger supports it and a USD payment needs it.

This README is completed as the service is built (API, design decisions, guarantees for consumers,
improvements).

## AI usage

See [AI_USAGE.md](AI_USAGE.md).
