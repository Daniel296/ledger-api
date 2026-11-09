# Ledger API

A double-entry ledger service: money is held in accounts and moves only through balanced, immutable
transactions. Transactions are recorded through a REST API and from payment-settled messages, and
every recorded transaction is announced to other services through Kafka.

## Stack

Java 17, Spring Boot 4, MySQL, Kafka, Maven (wrapper included).

## Build and test

Requires a JDK 17+ on the `PATH` (or `JAVA_HOME`).

```bash
./mvnw verify
```

Running everything in Docker with a single script arrives with the Docker setup; this README is
completed as the service is built (API, design decisions, guarantees for consumers, improvements).

## AI usage

See [AI_USAGE.md](AI_USAGE.md).
