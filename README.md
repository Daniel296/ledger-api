# Ledger API

A double-entry ledger. Money is held in accounts and moves only through transactions whose debits and credits
balance. A recorded transaction is never changed: a mistake is corrected by a reversal, which is a new transaction.
Transactions are recorded through a REST API and from `payment-settled` messages, and every recorded transaction is
announced on Kafka.

Java 17, Spring Boot 4, Spring Data JPA (Hibernate), MySQL 8.4, Liquibase, Kafka 4.2, Maven (wrapper included).

## Run

You need Docker, Git and a JDK 17 or newer.

```bash
git clone https://github.com/Daniel296/ledger-api.git
cd ledger-api
./run.sh
```

`run.sh` builds the service, starts it in Docker with MySQL and Kafka, waits until it is healthy, then runs the tests.
The tests start their own MySQL and Kafka (Testcontainers), so they never touch the running service's data.

- API: `http://localhost:8080` (health: `/actuator/health`). Contract: [docs/openapi.yaml](docs/openapi.yaml).
- Kafka messages: [docs/asyncapi.yaml](docs/asyncapi.yaml). From the host, Kafka is `localhost:9094`.
- MySQL: `localhost:3308`, database, user and password `ledger`.
- Stop everything: `docker compose -f docker/docker-compose.yml down -v` (`-v` also deletes the data).

## Examples

They follow the challenge's examples: Merchant 42 is paid 125.50 EUR, paid out 98.50 EUR with a fee of 1.50, and the
payout is reversed. Run them in this order, from the repository, on the stack `run.sh` started. Each answer is shown
under its command as a comment, trimmed.

**Accounts.** Every `POST` needs an `Idempotency-Key`. The same request with the same key is a retry: it gets the same
answer and opens nothing. The id is the first field of every answer, so the commands keep it in a variable.

```bash
curl -i localhost:8080/v1/accounts -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: open-merchant-42' -d '{"currency": "EUR"}'
# HTTP/1.1 201
# Location: http://localhost:8080/v1/accounts/1f25741e-b407-4fa3-b49c-d811626e53ff
# {"id":"1f25741e-b407-4fa3-b49c-d811626e53ff","currency":"EUR","overdraftLimit":"0.00","balance":"0.00",…}

MERCHANT=$(curl -s localhost:8080/v1/accounts -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: open-merchant-42' -d '{"currency": "EUR"}' | cut -d '"' -f 4)
PAYOUTS=$(curl -s localhost:8080/v1/accounts -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: open-payouts-eur' -d '{"currency": "EUR"}' | cut -d '"' -f 4)
FEES=$(curl -s localhost:8080/v1/accounts -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: open-fees-eur' -d '{"currency": "EUR"}' | cut -d '"' -f 4)
```

**A settled payment** (example 1). The script plays the payment system: it publishes a `payment-settled` message with
Kafka's console producer, inside the Kafka container, under a new event id every time. The ledger records it within a
second or two. Sending the same `paymentId` again is a redelivery and records nothing.

```bash
./scripts/send-payment-settled.sh "$MERCHANT" 125.50 EUR pay-42
# Sent: {"eventId": "330b21f3-…", "paymentId": "pay-42", "merchantAccountId": "1f25741e-…", "amount": "125.50", …}

curl localhost:8080/v1/accounts/$MERCHANT
# {"id":"1f25741e-…","currency":"EUR","overdraftLimit":"0.00","balance":"125.50",…}
```

**A transaction** (example 2), read back, then one refused for the overdraft limit (example 5). `X-Request-Id` is
optional; it comes back on the response and goes into the logs and the announcement.

```bash
PAYOUT_REQUEST='{"description": "Payout to Merchant 42", "entries": [
    {"accountId": "'$MERCHANT'", "direction": "DEBIT", "amount": "100.00"},
    {"accountId": "'$PAYOUTS'", "direction": "CREDIT", "amount": "98.50"},
    {"accountId": "'$FEES'", "direction": "CREDIT", "amount": "1.50"}]}'
curl -i localhost:8080/v1/transactions -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: payout-42' -H 'X-Request-Id: payout-42' -d "$PAYOUT_REQUEST"
# HTTP/1.1 201
# X-Request-Id: payout-42
# Location: http://localhost:8080/v1/transactions/199b4524-9fb9-4e9e-9893-6a206255fc62
# {"id":"199b4524-…","description":"Payout to Merchant 42","currency":"EUR",
#  "entries":[{"accountId":"1f25741e-…","direction":"DEBIT","amount":"100.00"},
#             {"accountId":"d60ddaf5-…","direction":"CREDIT","amount":"98.50"},
#             {"accountId":"edb7a92d-…","direction":"CREDIT","amount":"1.50"}],
#  "recordedAt":"2026-11-10T23:07:44.199618Z"}

PAYOUT=$(curl -s localhost:8080/v1/transactions -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: payout-42' -d "$PAYOUT_REQUEST" | cut -d '"' -f 4)
curl localhost:8080/v1/transactions/$PAYOUT
# the same transaction

curl -i localhost:8080/v1/transactions -H 'Content-Type: application/json' \
    -H 'Idempotency-Key: payout-too-large' -d '{"description": "Payout too large", "entries": [
    {"accountId": "'$MERCHANT'", "direction": "DEBIT", "amount": "50.00"},
    {"accountId": "'$PAYOUTS'", "direction": "CREDIT", "amount": "50.00"}]}'
# HTTP/1.1 422
# {"errorCode":"OVERDRAFT_LIMIT_EXCEEDED",
#  "message":"Account 1f25741e-… would go to -24.50, past its overdraft limit of 0.00"}
```

The other refusals of example 5 answer `422` too, and record nothing: `UNBALANCED`, `TOO_MANY_DECIMAL_PLACES`,
`CURRENCY_MISMATCH`, `TOO_FEW_ENTRIES` and `UNKNOWN_ACCOUNT`; an account in `EURO` or `ABC` gets `INVALID_CURRENCY`, one
in `GBP` `UNSUPPORTED_CURRENCY`. The message names the value at fault.

**A reversal** (example 3), then a second one, refused. `ASOF` notes the time before it, for the past balance below.

```bash
sleep 1; ASOF=$(date -u +%Y-%m-%dT%H:%M:%SZ)

curl -i -X POST localhost:8080/v1/transactions/$PAYOUT/reversal -H 'Idempotency-Key: reverse-payout-42'
# HTTP/1.1 201
# Location: http://localhost:8080/v1/transactions/11838a6f-9410-49d1-976d-12c6f73d3917
# {"id":"11838a6f-…","description":"Reversal of 199b4524-…","currency":"EUR","entries":[…],
#  "recordedAt":"2026-11-10T23:07:45.909786Z","reversalOf":"199b4524-9fb9-4e9e-9893-6a206255fc62"}

curl -i -X POST localhost:8080/v1/transactions/$PAYOUT/reversal -H 'Idempotency-Key: reverse-payout-42-again'
# HTTP/1.1 409
# {"errorCode":"TRANSACTION_ALREADY_REVERSED","message":"Transaction 199b4524-… was already reversed"}
```

**Entries and a past balance.** Entries come newest first, each with the balance after it; the next page starts after
`nextCursor`.

```bash
curl "localhost:8080/v1/accounts/$MERCHANT/entries?limit=2"
# {"entries":[{"transactionId":"11838a6f-…","direction":"CREDIT","amount":"100.00","balanceAfter":"125.50",…},
#             {"transactionId":"199b4524-…","direction":"DEBIT","amount":"100.00","balanceAfter":"25.50",…}],
#  "nextCursor":"3"}
curl "localhost:8080/v1/accounts/$MERCHANT/entries?limit=2&cursor=3"
# {"entries":[{"transactionId":"571f8335-…","direction":"CREDIT","amount":"125.50","balanceAfter":"125.50",…}]}

curl "localhost:8080/v1/accounts/$MERCHANT/balance?asOf=$ASOF"
# {"accountId":"1f25741e-…","currency":"EUR","balance":"25.50","asOf":"2026-11-10T23:07:45Z"}
```

**Dead letters and announcements.** A payment the ledger cannot record goes unchanged to `payment-settled-dlt`, with
the reason in its headers. Every recorded transaction is announced on `transaction-recorded`. Stop each consumer with
Ctrl-C.

```bash
./scripts/send-payment-settled.sh 00000000-0000-0000-0000-000000000000 10.00 EUR
docker compose -f docker/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic payment-settled-dlt --from-beginning --formatter-property print.headers=true
# errorCode:UNKNOWN_ACCOUNT,errorMessage:Account 00000000-0000-0000-0000-000000000000 does not exist,… {"eventId":…}

docker compose -f docker/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic transaction-recorded --from-beginning --formatter-property print.key=true \
    --formatter-property print.headers=true
# correlationId:pay-42     571f8335-…  {"id":"571f8335-…","description":"Payment pay-42 settled",…}
# correlationId:payout-42  199b4524-…  {"id":"199b4524-…","description":"Payout to Merchant 42",…}
# correlationId:2b8d6a03-… 11838a6f-…  {"id":"11838a6f-…","description":"Reversal of 199b4524-…",…}
```

## Decisions

**API.** Resources under `/v1`: accounts with their entries and past balances, transactions, and the reversal of a
transaction (singular: a transaction is reversed at most once). `v1` only grows, so clients ignore unknown fields.
Amounts are decimal strings, `BigDecimal` in the code and `DECIMAL(19, 4)` in MySQL, so `0.10 + 0.20` is exactly
`0.30`; a JSON number is accepted too and read exactly. Errors are `{"errorCode", "message"}`: `400` malformed (Spring
Boot's standard error body), `404` unknown id, `409` already reversed or a reversal, `422` a broken rule, `503`
accounts busy. The contracts came before the code, and the tests check every response and message against them.

**Pessimistic locking.** Recording a transaction locks its accounts in one `SELECT … FOR UPDATE`, in id order, then
checks the rules and writes, in one database transaction. No overdraft limit is broken, no update is lost, and A → B
and B → A cannot deadlock, on any number of instances, since they share only MySQL. Optimistic locking would retry all
the time: every payment in a currency touches its settlement account. A lock not obtained in 5 seconds is answered
`503` with `Retry-After`, and nothing is recorded. A `UNIQUE` index on `reversal_of` allows one reversal per
transaction.

**Idempotency.** The key is first reserved, in a short transaction of its own. The transaction that records the effect
then locks the key's row and stores in it a hash of the request and the answer, with the effect: both are committed or
neither. A retry gets the stored answer, on any instance; a copy sent at the same time waits for the first; after a
crash before the commit, the retry runs as a first attempt. A refusal (`404`, `409`, `422`) is stored like a success;
`400` and `503` are not stored. The same key with a different request gets `422 IDEMPOTENCY_KEY_REUSED`.

**Outbox and Kafka.** Kafka keeps messages for replay, orders them within a partition and shares partitions among the
instances of a consumer group. A recorded transaction writes its message to an `outbox` table, in the same database
transaction. A relay runs every 200 ms on every instance; only the one holding the lock on the single
`outbox_publisher` row publishes, in outbox order, to `transaction-recorded` (one partition), waits for each
acknowledgement, then marks the messages published. The message is the transaction as the API returns it, keyed by
its id, with the `X-Request-Id` (or the payment id) in a `correlationId` header.

**The payment-settled consumer.** Every instance consumes `payment-settled` in the group `ledger`. A payment debits the
settlement account of its currency and credits the merchant, through the same idempotency, with the key
`payment-settled:<paymentId>`, so a copy under any event id records nothing. The offset is committed after the
transaction. A refused message (malformed, unknown account, wrong currency) goes unchanged to `payment-settled-dlt`,
with `errorCode` and `errorMessage` headers, and the next one goes on; the refusal is not stored, so the payment can be
sent again once its cause is fixed. Any other failure (MySQL down, a busy account) is retried until it succeeds.

**Code structure.** `controller` (REST) and `messaging` (Kafka) call one method of a `service`, which owns the
database transaction and calls `validation` and `repository`, the only place with queries. The `model` classes are
plain JPA entities; every rule of a transaction is in `TransactionValidator`, checked against locked accounts and
unit-tested without Spring. Requests, responses and messages are DTOs (`dto`), never entities; every refusal is a
`LedgerException` with an `ErrorCode`. Tests are `unit` (plain JUnit) or `integration` (MySQL and Kafka in
Testcontainers, with a second instance of the service for the guarantees of section 6).

**What a consumer of `transaction-recorded` can rely on.**
- *Can it miss a transaction?* No. The message is committed with the transaction and stays in the outbox until Kafka
  acknowledges it. A refused transaction is never announced.
- *Can it see one twice?* Yes. A send not acknowledged within 5 seconds is sent again by the next relay run, and Kafka
  may still deliver the first attempt: during a 16-second Kafka pause, one transaction arrived 3 times. A relay that
  dies before marking a message published sends it again too. Copies are identical: deduplicate on the id (the key).
- *In what order?* Transactions on the same account arrive in the order they were applied, and a reversal after its
  original. Transactions with no account in common may arrive in either order.
- *What if the service crashes right after recording one?* The message is already in the outbox: another instance, or
  the same one restarted, publishes it. While Kafka is down, messages wait there and go out in order later.

## AI

How I used AI and how I checked its work: [AI_USAGE.md](AI_USAGE.md).

## Improvements

What I would change before production, going through the challenge's requirements.

**Model and amounts**
- `1e2147483647` overflows Hibernate Validator's `@Digits`: the API answers `500` instead of `400`, and a payment with
  it is retried forever, holding up its partition. `@DecimalMax("999999999999999.9999")` next to `@Digits` refuses it.
- A balance past 15 integer digits is refused by MySQL: `500` for a request, endless retries for a payment.
- Amounts are compared as written: a payment sent again as `10.0` instead of `10.00` is dead-lettered as a different
  request. Comparing them at the currency's scale would accept it.

**Settled payments**
- A message that fails every time for a bug stops its partition: alert on the lag of the consumer group `ledger`.
- Re-sending from `payment-settled-dlt` is manual (the script, same `paymentId`): a tool to replay chosen messages.
- The topics belong to the payment system; the ledger creates them only for the demo.

**Announcements**
- Duplicates during a Kafka outage: let the producer give up itself (`delivery.timeout.ms`) instead of the relay's
  5-second timeout, so a send the relay gave up on is never delivered later.
- Published rows stay in the outbox: delete them after a few days.
- One message at a time, about 600 a second: send in batches with the idempotent producer. A message Kafka always
  refuses stops the queue.
- A publisher that hangs without dying keeps the lock until MySQL drops its connection: a lease that expires instead.
- Metrics and alerts: messages not published, the age of the oldest. Reading the binlog (Debezium) instead of polling.
- More partitions for more consumers: one message per entry, keyed by account, keeps the order per account. The count
  is chosen up front, since adding partitions to a live topic breaks the order.

**Under pressure**
- Every payment in a currency queues on its settlement account: split it into several rows, summed for its balance.
- Idempotency keys never expire (the contract promises at least 24 hours): a `reserved_at` column and a purge job.
- `POST /v1/accounts` can answer `503` when a retry waits too long on its key; the contract does not list it.

**Entries, past balances and tracing**
- `recordedAt` is the recording instance's clock: a skew can misplace a transaction right at an `asOf`. MySQL's clock,
  read under the accounts' lock, would not.
- A past balance on a busy account reads every entry after `asOf`: copy the time onto entries, indexed with the account.
- Declare `X-Request-Id` on every response in `docs/openapi.yaml`, so the contract tests check it everywhere.

**Running and deploying**
- `.gitattributes` keeps LF only for `mvnw`: with Git for Windows' default `core.autocrlf`, `run.sh` gets CRLF and
  fails. `*.sh text eol=lf` fixes it.
- Spring profiles per environment (`application-prod.yml`), with secrets outside `docker-compose.yml`.
- Expand/contract migrations, so two versions run side by side during a deploy; stored idempotent answers must stay
  readable too (those stored before `currency` was added to `Transaction` lack it).
- A crash during a migration leaves Liquibase's lock taken: release it as part of the deploy.
- In CI: oasdiff for breaking API changes, the AsyncAPI CLI on `docs/asyncapi.yaml` (never run yet), a dependency scan.
- Time-ordered UUIDs (version 7) as `BINARY(16)`: smaller indexes, inserted in order.

**Security** (not implemented: the challenge does not ask for it)
- Authentication: every caller, internal service or merchant, identifies itself.
- Authorization: a merchant reads only its own accounts and cannot move another merchant's money (`403`); payouts,
  fees, reversals and the settlement accounts are for internal services only.
- Idempotency keys per caller (caller + key) instead of global, as the IETF idempotency-key draft recommends.
- Kafka with TLS and ACLs: only the payment system may publish `payment-settled`.
- Log injection: a `paymentId` may hold a line break and reaches the logs; restrict it like `X-Request-Id`, or log JSON.
- Already in place: DTOs separate from entities, parameterised queries, at most 100 entries per transaction, bounded
  amounts, no stack traces in errors, random UUIDs, locks against races, idempotency against replays.
