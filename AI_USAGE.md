# AI usage

I used Claude Code (Anthropic) as a pair programmer throughout this project.

## What it was used for

- Analysing the challenge and breaking it into small, reviewable merge requests.
- Generating the project skeleton and drafting code and tests, step by step, under my direction.

## How its output was checked

- Every change is built and tested (`./mvnw verify`) before it is committed.
- I read every diff before committing it; design decisions are mine and are explained in the README.

## Instruction files

The instructions given to the assistant are committed in [CLAUDE.md](CLAUDE.md).
