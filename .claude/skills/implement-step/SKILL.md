---
name: implement-step
description: >-
  The process for implementing one step of this project's plan - contract first, code with its tests,
  automatic checks, a simplification pass, an independent review on another model, triage of the
  findings, and commits split for review. Use whenever the user asks to implement a step or the next
  point of the plan ("implement step 7", "next step", "implementeaza pasul 7", "punctul urmator").
---

# Implementing a step

The service is built in small steps, each ending in one or two reviewable commits. The coding rules
live in [CLAUDE.md](../../../CLAUDE.md); this file is the process every step follows.

## 1. Scope

- Read what the step must deliver: the user's request, the plan, and the part of the challenge
  statement it covers. Read the notes earlier steps left for this one.
- Settle design questions with the user before writing code, each with a recommendation.

## 2. Contract first

- If the step changes the API, update `docs/openapi.yaml` before the code and validate it.
- Tell the user every addition to the contract, and every removal of something that turned out not
  to be needed.

## 3. Code and tests together

- Production code and its tests land in the same change.
- Unit tests (plain JUnit, `...ledger.unit`) cover the logic; integration tests (`...ledger.integration`,
  `@IntegrationTest`) run against real MySQL (and Kafka once it is in) through Testcontainers, and every
  response they receive is checked against the contract automatically.
- Keep only what the step needs: no config, dependency, class, branch or parameter that nothing uses.

## 4. Checks

- `./mvnw verify` is green.
- JaCoCo (`target/site/jacoco`) shows 100% of lines and branches, with no test that only restates the code.
- No line longer than 128 characters, no string built with `+`.

## 5. Simplify

Re-read the whole diff as someone else's code and remove what the next reader would delete.

## 6. Independent review

- When the implementation passes the checks, write the step's name into `.claude/review-pending`.
  A Stop hook then refuses to end the turn until the review has been launched.
- Launch the `ledger-reviewer` agent (it runs on a different model from the one that wrote the code) on
  the uncommitted diff, with the step's scope and the decisions already taken, then delete
  `.claude/review-pending`.

## 7. Triage

Every finding ends in one bucket, and the report says which: fixed now; a decision for the user, with a
recommendation; noted for a later step; or dropped, with the reason. Fixes are followed by the checks
again.

## 8. Documentation

Update the README sections the step touches, and CLAUDE.md when a new rule was agreed.

## 9. Commits

- Never commit or push: the developer reviews and commits.
- Split the change into commits of at most 20 files (scaffolding and infrastructure excepted), with
  pure style or test refactors in their own commit first.
- Prove each commit builds and passes on its own: extract `HEAD` into a scratch folder
  (`git archive HEAD | tar -x -C <dir>`), copy that commit's files over it and run `./mvnw verify`.
- Hand over the exact commands, `git add <files> && git commit -m "<message>"`, with short imperative
  messages and no AI attribution.

## Report

Files changed, decisions taken, contract changes, the review's findings with their bucket, and the test
and coverage results.
