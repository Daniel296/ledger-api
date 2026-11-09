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

## Persistence decisions

- The schema is owned by Liquibase changelogs written as formatted SQL; Hibernate only validates
  it (`ddl-auto: validate`), so the constraints the ledger relies on are explicit and reviewable.
- `open-in-view` is off: entities never leave a service transaction.
- Paginating over a collection fetch fails instead of silently paging in memory
  (`hibernate.query.fail_on_pagination_over_collection_fetch`).

This README is completed as the service is built (API, design decisions, guarantees for consumers,
improvements).

## AI usage

See [AI_USAGE.md](AI_USAGE.md).
