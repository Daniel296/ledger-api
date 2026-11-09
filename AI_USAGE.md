# AI usage

I used Claude Code (Anthropic) as a pair programmer throughout this project.

## What it was used for

- Analysing the challenge and breaking it into small, reviewable merge requests.
- Generating the project skeleton and drafting code and tests, step by step, under my direction.

## How its output was checked

- Every change is built and tested (`./mvnw verify`) before it is committed, with 100% line and branch
  coverage and every API response checked against the OpenAPI contract.
- Each step is reviewed by a second model, independent of the one that wrote the code, and every
  finding is fixed, decided with me, scheduled for a later step or dropped with a reason.
- I read every diff and make every commit myself; design decisions are mine and are explained in the
  README.

## Instruction files

Everything the assistant was given is committed:

- [CLAUDE.md](CLAUDE.md): the project's rules (stack, domain rules, code and test conventions).
- [.claude/skills/implement-step](.claude/skills/implement-step/SKILL.md): the process every step
  follows, from contract to commits.
- [.claude/agents/ledger-reviewer.md](.claude/agents/ledger-reviewer.md): the independent reviewer.
- [.claude/settings.json](.claude/settings.json) and [.claude/hooks/require-review.sh](.claude/hooks/require-review.sh):
  a hook that does not let a step end before its review has been launched.
