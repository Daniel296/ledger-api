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

The contract is written first and lives in [docs/openapi.yaml](docs/openapi.yaml). Every response an
integration test receives is checked against it (status, headers, body), and every operation a test
calls must exist in it. What no test calls is not checked, so each endpoint comes with its tests.

| Method | Path | |
|---|---|---|
| `POST` | `/v1/accounts` | Open an account |
| `GET` | `/v1/accounts/{accountId}` | Read an account with its balance |
| `GET` | `/v1/accounts/{accountId}/entries` | An account's entries, newest first, paged, with the balance after each |
| `GET` | `/v1/accounts/{accountId}/balance?asOf=…` | An account's balance as at a past instant |
| `POST` | `/v1/transactions` | Record a transaction |
| `GET` | `/v1/transactions/{transactionId}` | Read a transaction with its entries |
| `POST` | `/v1/transactions/{transactionId}/reversal` | Reverse a transaction (at most once, hence singular) |

- **Amounts are decimal strings** (`"125.50"`), so no client reads them as floating point.
- **Every `POST` needs an `Idempotency-Key`** (`400` without one): a retry with the same key and the
  same request gets the original response (status, body and `Location`) and takes effect at most once;
  once a request has taken effect, the same key with a different request, on any endpoint, is refused
  with `422 IDEMPOTENCY_KEY_REUSED`.
  See [Idempotency](#idempotency).
- **Errors** are `{"errorCode", "message"}`: a stable code a calling system can act on, and a
  message naming the value at fault. `400` malformed request, `404` unknown resource in the path, `409`
  conflicting state (already reversed, or itself a reversal), `422` a ledger rule is broken or an
  idempotency key is reused for a different request.
- **Versioned in the path**: `v1` only evolves additively, so clients must ignore unknown fields.
- **`X-Request-Id`** (optional, generated when missing) is returned on the response and carried into
  the logs and the announcements a request causes.
- **Entries are paged by cursor**, not offset, so entries recorded meanwhile never shift a page.

## Idempotency

A client that gets no answer can send the same `POST` again with the same `Idempotency-Key`.

- **The key is stored with the effect.** The key is first reserved: an empty row, inserted and committed
  on its own. The transaction that records the account or transaction then locks that row
  (`SELECT … FOR UPDATE`) and writes into it a SHA-256 of the request and the response, together with the
  effect. If that transaction commits, both are there; if it does not (a refused request, an error, an
  instance that dies before the commit), the row stays empty and the retry runs as if it were the first
  attempt.
- **The same request** means the same operation, the same id in the path and the same body as read:
  spacing and field order do not matter, a different value does, and so does an amount written with
  another scale (`10.0` for `10.00`). Keys are global and compared byte for byte, so `abc` and `ABC` are
  two keys.
- **The response is stored, not rebuilt**: a retried account opening shows the balance the account had
  when it was opened, and the `Location` is built from the stored id.
- **Only what took effect is remembered.** A refused request leaves an empty reservation, so its retry is
  judged again against the balances of that moment: it may now succeed, and it never takes effect twice.
  The key is not tied to the refused request either: it can still be used for a corrected one.
- **Copies sent at once wait for each other** on the reserved row's lock. A copy that arrives while the
  first attempt is still running waits, then answers with the stored response or, if the first attempt
  took no effect, runs itself; no client ever sees "still in progress". The reservation is committed
  before anyone waits on it: copies waiting on a row whose insert is then rolled back inherit gap locks
  in MySQL and deadlock each other, which the concurrency tests caught. The key is always locked before
  the accounts, so its lock cannot deadlock with theirs.
- **Any instance.** The key and its lock live only in the shared MySQL, so it does not matter which
  instance a retry reaches. The concurrency tests send the copies through the HTTP layer at once, each on
  its own thread, database connection and transaction: they meet only in the database, as copies sent to
  different instances would.
- **A crash before the commit** is tested by a first attempt that writes and then fails before its
  transaction commits, which MySQL rolls back as it does the open transaction of an instance that dies;
  the retry with the same key records the transaction once.
- The effect runs under `READ COMMITTED`, in a transaction `IdempotencyService` starts itself and never
  joins from a caller (it refuses to run inside one): the reads that decide anything (the key, the
  accounts) are locking reads, and a reversal needs it to see a reversal committed while it waited.
- Keys are kept for at least 24 hours, as the contract promises; for now they are never purged.

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
- Recording a transaction locks its accounts (`SELECT … FOR UPDATE`, always in id order) until the
  database transaction commits: the rules are checked against balances nobody else can change, and
  two transactions on the same accounts in opposite directions cannot deadlock.
- A transaction keeps its currency (all its entries share one), so it can be shown without reading
  its accounts.
- A reversal is a new transaction whose `reversal_of` points at the original, and a `UNIQUE` index on
  that column makes "reversed at most once" a database guarantee. Reversing first locks the original's
  accounts and only then looks for an existing reversal, under `READ COMMITTED`: two reversals of the
  same transaction sent at once queue on those locks, the second one sees the first and is refused with
  `409`, and the index never has to be the one that says no.
- Each entry keeps the balance of its account right after it, in the order of the request. An account
  posted to twice in one transaction may show an intermediate value below its limit; the overdraft
  rule is judged on the account's net change, since the transaction is applied all at once.
- The ledger's own settlement accounts are created by the migration, with no overdraft limit and
  fixed random ids: EUR `ffd6f25c-cbbb-43ae-afe5-1834678bb9aa`, USD
  `91509bf3-2df9-43f8-89e3-2283c7f5a97d`, JPY `7bba09b3-74f5-4fbd-9315-25c9c98e4f94`. The PDF names
  only EUR and JPY; USD gets one too, since the ledger supports it and a USD payment needs it.

## Correct under pressure

The ledger runs on several instances that share nothing but MySQL, so every guarantee rests on the
database, never on the JVM. Each one is proven against a real MySQL (Testcontainers), and every
race between requests runs five times:

| Guarantee | Test |
|---|---|
| No account past its overdraft limit, no lost update, no deadlock when money moves A → B and B → A at once, a transaction reversed at most once, with the requests split across two instances | `ConcurrentTransactionsShould` |
| Every currency adds up to zero and every balance equals the sum of its entries: checked again and again while 200 transactions and reversals race on two instances, and once more at the end | `LedgerIntegrityShould` |
| A request sent again with the same `Idempotency-Key` takes effect once: copies sent at once, to one instance or to both, and a first attempt that dies before its commit | `ConcurrentRetriesShould`, `ConcurrentTransactionsShould`, `IdempotencyServiceShould` |
| A request kept waiting on a locked account is answered `503` after 5 seconds and records nothing, while requests on other accounts are still served | `BusyAccountShould` |
| A refused transaction leaves nothing behind | `TransactionApiShould` |

- **Two instances in the tests**: the second instance is a second Spring Boot application started
  in the same JVM, with its own connection pool and its own Hibernate, connected to the same MySQL.
  Like two containers, the two instances share only the database.
- **Lock waits**: MySQL waits 50 seconds for a row lock by default; the ledger sets 5
  (`innodb_lock_wait_timeout`, on every connection). A request that waits longer, or that MySQL
  picks as the victim of a deadlock (none is expected: a transaction locks all its accounts in one
  statement, in id order), is rolled back and answered `503 ACCOUNT_BUSY` with `Retry-After: 1`. The ledger does not
  retry by itself: on a busy account a retry only makes the queue longer, and the client can retry
  safely with the same `Idempotency-Key`. A copy of a request waiting for its first attempt on the
  idempotency key's lock waits under the same 5 seconds, so when that attempt is held up by busy
  accounts, the copy is answered `503` too.
- **Connection pool**: a request keeps its database connection while it waits for a lock, so
  requests piling up on one busy account can hold every connection of the pool. The 5-second lock
  wait gives each of them back well before the 30 seconds Hikari lets a request wait for a
  connection, so a burst on one account delays requests on other accounts by seconds instead of
  failing them. A flood that never stops on one account would still starve the others; splitting
  the settlement account is in Improvements. The pool keeps Hikari's default of 10 connections per
  instance: size it to the requests an instance serves at once, and keep instances × pool size below
  MySQL's `max_connections` (151 by default).
- **Largest balance**: amounts and balances are `DECIMAL(19, 4)`, with 15 integer digits. A larger
  amount in a request is refused with `400`. A balance gets there only after a quadrillion has piled
  up; MySQL then refuses the update and the whole transaction is rolled back (`500`, nothing
  recorded).
- **Isolated tests**: before each integration test, `@IntegrationTest` empties the ledger and runs the
  settlement accounts' migration again, so the checks over the whole ledger see only the test's own data.

This README is completed as the service is built (API, design decisions, guarantees for consumers,
improvements).

## AI usage

See [AI_USAGE.md](AI_USAGE.md).
