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

The script builds the service image, starts it with MySQL and Kafka through Docker Compose, waits
until it is healthy, then runs the test suite. The tests start their own MySQL and Kafka through
Testcontainers, so they never touch the running service's data.

The service listens on `http://localhost:8080`; `GET /actuator/health` reports its status. Its MySQL is
reachable from the host at `jdbc:mysql://localhost:3308/ledger` (user `ledger`, password `ledger`), for a
database client; 3308 avoids clashing with a local MySQL on 3306. Kafka is reachable from the host at
`localhost:9094`, for a Kafka client: a second listener, since the service and the other containers use
`kafka:9092`, a name that exists only inside the Docker network.
Stop everything with `docker compose down` (add `-v` to also drop the database volume).

To send a sample payment-settled message, as the payment system would (the script runs Kafka's console producer
inside the Kafka container, so nothing needs to be installed):

```bash
./scripts/send-payment-settled.sh <merchantAccountId> <amount> <currency> [paymentId]
```

Every message gets a new event id; passing the `paymentId` of an earlier one sends that payment again, which the
ledger records only once. Messages it cannot record are set aside on `payment-settled-dlt`, with the reason in their
headers:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic payment-settled-dlt --from-beginning --formatter-property print.headers=true
```

To watch the announcements of the running service:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic transaction-recorded --from-beginning --formatter-property print.key=true
```

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
  See [Statement and past balances](#statement-and-past-balances).

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

## Settled payments

The payment system announces every settled payment on the Kafka topic `payment-settled`; the message contract is
[docs/asyncapi.yaml](docs/asyncapi.yaml). Every instance consumes it, in the consumer group `ledger`, and records each
payment as a transaction that debits the settlement account of its currency and credits the merchant's account,
described as `Payment <paymentId> settled` and announced on `transaction-recorded` like any other:

```
payment-settled message, on any instance
└─ PaymentSettledConsumer.consume        read the JSON: every field there and well formed, or MALFORMED_MESSAGE
   └─ TransactionService.settle          debit the settlement account of the currency, credit the merchant
      └─ IdempotencyService.commitOnce   key payment-settled:<paymentId>; hash of paymentId, merchant, amount, currency
         └─ as POST /v1/transactions     lock the accounts, check the rules, save, outbox; commit
└─ commit the offset ◄── only once the transaction is committed
```

- **Recorded once, however often it arrives.** The payment id is the idempotency key (`payment-settled:<paymentId>`;
  keys are global, and the prefix keeps them apart from the REST clients' keys), and the request hash covers the
  payment id, merchant, amount and currency, never the event id. A copy, under the same event id or a new one, finds
  the stored transaction and records nothing; copies reaching two instances at once queue on the key's lock, as REST
  retries do. A message with the same payment id but another merchant, amount or currency is not a copy: it is refused
  with `IDEMPOTENCY_KEY_REUSED` and dead-lettered.
- **Nothing is lost when an instance crashes.** The offset is committed only after the transaction (Spring Kafka's
  default), so an instance that dies in between leaves the message to be delivered again, to itself or to another
  instance, which recognizes it.
- **A message the ledger cannot record is set aside, not lost, and does not block the ones behind it.** Malformed
  JSON, a missing field, an unknown account, a merchant account in another currency, an amount that is not positive
  or has too many decimals: the message goes unchanged (same key, same value) to `payment-settled-dlt`, on the same
  partition, with the headers `errorCode` and `errorMessage`, and the consumer moves on. A refusal is not remembered,
  so a dead-lettered payment can be sent again once its cause is fixed: for a merchant whose account was not open
  yet, open the account and send the payment again with the same `paymentId`. The same message can land on the
  dead-letter topic twice, when it is delivered again before its offset was committed; sending it again still
  records it once.
- **Any other failure is retried, never dead-lettered.** An error that is not one of the ledger's refusals (the
  database unavailable, an account locked for longer than 5 seconds, a bug) is retried on the same message, 2, 3, 4.5
  and up to 30 seconds apart (Spring's default back-off), until it succeeds: dead-lettering it would set aside a
  payment that is fine. The messages behind it on that partition wait, so a bug that fails one message every time
  stops its partition until it is fixed, which shows as consumer lag; so would a merchant balance growing past 15
  integer digits, which MySQL refuses.
- **Two partitions, one consumer thread per instance.** Every payment in a currency locks the same settlement account,
  so more threads would not record faster; the two partitions let two instances share the work. The ledger creates
  `payment-settled` and `payment-settled-dlt` at startup, for the demo; in production the payment system would own
  them.

## Announcements

Every recorded transaction, reversals included, is announced on the Kafka topic `transaction-recorded`,
as the same JSON the API returns for it. The message contract is [docs/asyncapi.yaml](docs/asyncapi.yaml):
its payload is the `Transaction` schema of the OpenAPI contract, and every message an integration test
reads is checked against it.

Two flows, each in its own database transaction. Recording a transaction (or a reversal) writes its
message to the outbox:

```
POST /v1/transactions
└─ TransactionService.post
   └─ IdempotencyService.commitOnce              one READ COMMITTED transaction ◄── starts here
      ├─ lock the idempotency key                SELECT … FOR UPDATE
      ├─ TransactionService.apply
      │   ├─ lock the accounts, check the rules  SELECT … FOR UPDATE, in id order
      │   ├─ save the transaction                INSERT transactions, entries; UPDATE accounts
      │   └─ OutboxService.add                   INSERT outbox  (joins the transaction, never opens one)
      ├─ keep the response on the key            UPDATE idempotent_requests
      └─ commit ◄── balances, transaction, entries and message: all of them, or none
```

and the relay, which knows nothing about requests, publishes it:

```
every 200 ms, on every instance: OutboxRelay.publishPending, in its own READ COMMITTED transaction
├─ lock the one outbox_publisher row     SELECT … FOR UPDATE SKIP LOCKED; locked elsewhere → stop
├─ read up to 100 unpublished messages   SELECT … WHERE published_at IS NULL ORDER BY id
├─ send each to Kafka                    wait for its acknowledgement before sending the next
├─ mark them published                   UPDATE outbox SET published_at = now WHERE id IN (…)
└─ commit, which releases the lock ◄── any failure rolls back, and the batch is sent again later
```

- **Transactional outbox.** The message is written in the transaction that records the transaction, so
  both are committed or neither is. Kafka is never called inside that transaction: recording does not wait
  for Kafka, and keeps working while it is down.
- **A relay publishes the outbox.** Every instance runs it every 200 ms. A run starts by locking the one
  row of `outbox_publisher` with `SELECT … FOR UPDATE SKIP LOCKED`; when another instance holds it, the
  run does nothing. The holder reads up to 100 messages not published yet, publishes them one by one in
  the order of their outbox id, waiting for Kafka's acknowledgement of each before sending the next, then
  marks them published and commits. A message is picked by its `published_at` flag, not as "after the
  last id published": ids are taken at insert, not at commit, so a later id can commit first and would
  be skipped. A send that fails, or is not taken and acknowledged within 5 seconds each, rolls the run
  back, and the batch is sent again by a later run. When the publishing instance dies, MySQL releases its
  lock with its connection and another instance takes over. An instance that hangs without dying, cut off
  from MySQL while its connection stays open, keeps the lock, and nobody publishes until MySQL drops that
  connection; a lease that expires would fix it (see Improvements).
- **Why one publisher and one partition.** Transactions that share an account are applied one after the
  other (they lock the account), and each inserts its message while it holds that lock, so their outbox
  ids follow the order they were applied in. A reversal is recorded only after its original was
  committed. Publishing in outbox id order, from one instance at a time, to a topic with one partition
  hands consumers that same order; two publishers at once, or several partitions, would interleave it.
  The price is throughput: one partition is read by one consumer of a group, and the relay publishes about
  600 messages a second (1,000 in 1.6 s, measured locally). Since it sends one message at a time, the
  producer does not wait to fill a batch (`linger.ms: 0`; with Kafka's default of 5 ms it was 170 a second).

What a consumer can rely on:

| | |
|---|---|
| **Can it miss a transaction?** | No. A committed transaction always has its message in the outbox, and the message stays there until Kafka has acknowledged it. A refused transaction is never announced. |
| **Can it see one twice?** | Yes, rarely: a relay that dies, or fails, after Kafka acknowledged a message but before it marked it published sends it again. The copy is identical (same key and body), so consumers deduplicate on the transaction id, which is also the message key. |
| **In what order?** | Two transactions that touch the same account arrive in the order they were applied to it, and a reversal always arrives after the transaction it reverses. Transactions with no account in common may arrive in either order. A copy always repeats a message that already arrived, so once copies are dropped the order still holds. |
| **What if the service crashes right after recording one?** | The transaction and its message were committed together, so the message waits in the outbox; any other instance (or the same one once restarted) publishes it within a relay run. If Kafka is down, messages wait in the outbox and are published, in order, once it is back. |

## Statement and past balances

- **The statement** (`GET /v1/accounts/{accountId}/entries`) lists an account's entries newest first, each with the
  account's balance right after it. `nextCursor` is the id of the page's last entry, and the next page holds the
  entries before it (`id < cursor`). On one account, ids follow the order in which transactions commit: an entry is
  inserted while its transaction holds the account's lock, until it commits. So a cursor never skips an entry or
  shows one twice.
- **The balance at a past instant** (`GET /v1/accounts/{accountId}/balance?asOf=…`) is the `balanceAfter` of the
  account's last entry whose transaction was recorded at or before `asOf`, and zero before its first entry.
  `recordedAt` comes from the clock of the instance that records the transaction, so the instances' clocks are
  expected to be kept in sync (NTP). A skew of a few milliseconds can misplace a transaction recorded right at
  `asOf`.
- **No new table or index.** Both read the index on `entries.account_id`, which InnoDB extends with the entry id.
  A past balance walks back through the entries recorded after `asOf`, so it slows down for an old instant on a
  busy account. Copying the time onto each entry, with an index on `(account_id, recorded_at)`, would turn it into
  a single look-up.

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
- The ledger's own settlement accounts are created by the migration, one per currency, with no
  overdraft limit and a random id; nothing in the code knows their ids. The ledger finds the one of a
  currency as the account with `unlimited_overdraft` in that currency (indexed, since every settled
  payment looks it up), and no request can open such an account. To see their ids:
  `SELECT id, currency FROM accounts WHERE unlimited_overdraft`. The PDF names only EUR and JPY; USD
  gets one too, since the ledger supports it and a USD payment needs it.

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
| A refused transaction leaves nothing behind, and is not announced | `TransactionApiShould`, `TransactionAnnouncementsShould` |
| Every transaction is announced, once and in the order it was applied, when both instances record transactions and reversals and both run the relay | `ConcurrentAnnouncementsShould` |
| A committed transaction is announced even when Kafka is down while it is recorded, or the instance publishing the outbox dies first; a publisher that dies after sending sends an identical copy | `OutboxRelayShould` |
| A settled payment is recorded once, whether it arrives again under the same or a new event id, on one instance or on both at once, or after an instance recorded it and died before committing its offset | `PaymentSettledConsumerShould`, `ConcurrentPaymentsShould` |
| A payment-settled message the ledger cannot record goes unchanged to the dead-letter topic, leaves nothing behind and does not hold up the messages after it; one that fails for another reason (a locked account) is retried until it is recorded | `PaymentSettledConsumerShould` |

- **Two instances in the tests**: the second instance is a second Spring Boot application started
  in the same JVM, with its own connection pool, its own Hibernate, its own outbox relay and its own
  `payment-settled` consumer in the same consumer group, connected to the same MySQL and Kafka. Like two
  containers, the two instances share only the database and the broker.
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
  It first takes the outbox publisher's lock, so a relay run still publishing the previous test's
  messages finishes before the outbox is emptied, and a test reads only the announcements it caused.

This README is completed as the service is built (API, design decisions, guarantees for consumers,
improvements).

## AI usage

See [AI_USAGE.md](AI_USAGE.md).

## Improvements

- **More partitions, for more consumers, when traffic grows.** The `transaction-recorded` topic has one
  partition so that consumers get transactions in the order they were applied: Kafka orders messages only
  within a partition, and one partition is read by one consumer of a group. When one consumer can no
  longer keep up, the topic can be split, but the key then decides what stays in order:
  - *by merchant* (all of a merchant's events in one partition): the ledger would first have to know which
    accounts belong to which merchant, and the order still breaks for a transaction that touches two
    merchants (a transfer between them lands in one merchant's partition only) and for the ledger's own
    accounts (Settlement EUR is in every EUR payment, so its events spread over every partition);
  - *by the original transaction* (`reversalOf` for a reversal, its own id otherwise): a reversal still
    arrives after its original, but two transactions on the same account may swap;
  - *one message per entry, keyed by account*: every account, Settlement included, keeps its order; a
    transaction becomes several messages, which a consumer joins by transaction id. This is the one that
    keeps today's promise per account.

  Whatever the key, the relay still publishes from one instance at a time, in outbox order (two publishers
  would race within a partition), so more partitions do not publish faster; sending in batches would. The
  number of partitions is chosen up front: adding partitions to a live topic moves keys to other
  partitions and breaks their order while it happens, so the clean way is a new topic.
