# Instructions for the AI assistant

## Project

A double-entry ledger service (coding challenge). Stack: Java 17, Spring Boot 4, Spring Data JPA
(Hibernate), MySQL, Liquibase, Kafka, Maven. Everything must run in Docker; `run.sh` builds the
service, starts it and runs the tests.

## Persistence rules

- Liquibase owns the schema, as formatted SQL changelogs; Hibernate only validates (`ddl-auto: validate`).
- A committed changeset is never edited: every schema change is a new changeset.
- Accounts are locked pessimistically (`@Lock(PESSIMISTIC_WRITE)`), always in ascending id order.
- Every `POST` writes through `IdempotencyService.commitOnce`: it reserves the idempotency key in its own
  committed transaction, then runs the effect in a `READ COMMITTED` transaction that locks the key before any account;
  it refuses to run inside another transaction.
- Transactions and entries are `@Immutable`.
- Every recorded transaction is announced through the outbox: `OutboxService.add` writes its message in the
  transaction that records it (it refuses to run outside one). `OutboxRelay` publishes it: only the instance holding
  the lock on the one `outbox_publisher` row publishes, in outbox id order, to the one-partition topic
  `transaction-recorded`; it picks messages by their `published_at` flag, never as "after the last id".
- `open-in-view` stays off; explicit queries use `@Query`.
- Never paginate over a collection fetch; page the account statement with keyset, not offset.
- Add a dependency or a setting only when code needs it.

## Domain rules that must never be broken

- A transaction has at least two entries; its debits and credits add up to the same total.
- All accounts of a transaction exist and share one currency (EUR, USD, JPY; 2, 2 and 0 decimals).
- No account goes past its overdraft limit. A transaction is recorded all or nothing.
- Transactions and entries are never updated or deleted; a mistake is corrected by a reversal,
  which can happen only once and cannot itself be reversed.
- Money is exact: `BigDecimal` in code, `DECIMAL` in MySQL, never `double` or `float`.

## Code

- Models (`model`) are plain data classes with Lombok getters and setters, no business rules.
  Rules live in validators (`validation`), which services call before changing anything.
- Layering: controller → service → repository. Controllers stay thin; business rules live in
  services; SQL lives only in repositories. Transactions are owned by the service layer.
- Packages follow the layers, grouped into subfolders: `dto/request` and `dto/response`,
  `exception/handler`, `model/enums`; Kafka clients live in `messaging`. Integration tests sit in
  `integration/api`, `integration/repository`, `integration/service` and `integration/concurrency`, with the shared
  setup in `integration/support`.
- DTOs are named after the resource in the URL: `<Resource>Request` for what the client sends,
  `<Resource>Response` for what it gets back (`AccountRequest`, `AccountResponse`). The OpenAPI request
  schemas carry the same names; response schemas are the bare resource (`Account`).
- Constructor injection only, depending on abstractions.
- Lines are at most 128 characters.
- Call chains with more than two calls (streams, builders, requests): one call per line, with
  `.stream()` kept next to its source (`entries.stream()`). Assertions may stay on one line.
- Method order in controllers, services and repositories: reads (get) first, then create (post), then update
  (put), then delete; private helpers last.
- No method or variable named after a restricted identifier (`record`, `var`, `yield`, `sealed`, `permits`).
- Never build strings with `+`: use `"...%s...".formatted(...)` (and `{}` placeholders in log calls).
- No comments and no Javadoc: method and variable names say what the code does.
- Keep code airy: a blank line after a class, enum, method or loop opening line, after every closed
  block (`}`) that more code follows, and between logical groups of statements.
- Validators stop at the first broken rule and throw a `LedgerException` (code, message).
- The ledger's own errors are one `LedgerException` carrying an `ErrorCode`. The API answers them as
  `{"errorCode", "message"}`, and `exception/handler/ApiExceptionHandler` picks the HTTP status from the code
  (404 not found, 409 conflict, 422 for every broken rule). A lock not obtained in time (lock wait timeout,
  deadlock) is answered 503 `ACCOUNT_BUSY` with `Retry-After`. Errors of the HTTP layer (400 malformed request,
  405, 415, 500) are left to Spring Boot's standard handling. Validators never know request field names; the
  message names the value at fault (the amount, the account id).
- Error codes are a single `ErrorCode` enum in `exception`, used through static imports; so are the
  `Currency` constants (`EUR`, not `Currency.EUR`).
- An account with no overdraft limit (the settlement accounts) is marked explicitly with
  `unlimitedOverdraft`; `overdraftLimit` is never null and defaults to zero.
- Never query inside a loop; never re-read data already loaded.

## Tests

- Test classes end in `Should` (`MoneyShould`), never `Test`; methods read as the rest of the
  sentence (`rejectMoreDecimalsThanTheCurrencyAllows`).
- Unit and integration tests are never mixed: unit tests live under `com.coremaker.ledger.unit`
  (plain JUnit, no Spring), integration tests under `com.coremaker.ledger.integration`, annotated
  `@IntegrationTest`.
- Keep tests as simple as possible; add test helpers only when a test needs them.
- No shared test data in fields: each test builds the objects it needs through general helpers
  (`account(...)`, `debit(...)`); only the class under test and its mocks are fields.
- Integration tests check every response against `docs/openapi.yaml` (`OpenApiContractConfiguration`), and every
  `transaction-recorded` message they read against its payload schema (`Announcements`).
- Unit tests cover 100% of lines and branches of the unit-testable code (JaCoCo; Lombok-generated
  code is excluded through `lombok.config`), with no test that only restates the code.
- Guarantees (concurrency, idempotency, redelivery, integrity, refused transactions leaving nothing
  behind) are proven against the real MySQL and Kafka via Testcontainers, not mocks. Several instances are
  proven with `SecondInstance`, a second application context on the same MySQL and Kafka.
- Every integration test starts from an empty ledger: `@IntegrationTest` waits for the outbox publisher, deletes
  everything and runs the settlement accounts' migration again. A test reads only the announcements published
  after it started listening (`Announcements.fromNowOn`).
- Every behaviour change comes with its test in the same change.

## Workflow

- Every step follows [the implement-step skill](skills/implement-step/SKILL.md) and ends with an
  independent review by the `ledger-reviewer` agent.
- Work is split into commits of at most 20 files, except the project skeleton and the
  Docker / test infrastructure. The developer makes every commit.
- The API contract (`docs/openapi.yaml`) and the message contract (`docs/asyncapi.yaml`) are written before the code
  and kept in step with it.
- Commit messages are short, in the imperative mood, and carry no AI attribution.
