# Cloud-Native Financial Settlement Platform

> `main` is built by **consuming** the E2A framework family: the AI-DLC pipeline scaffolds each
> service (P0 + G2C + A2C), the settlement service realises E2A's Deterministic CQRS profile in
> Java, and the audit service runs an E2A agent with retrieval over policy clauses.

| Service | Stack | Role |
|---|---|---|
| `settlement-service` | Java 21, Spring Boot 3.5, PostgreSQL (H2 locally) | System of record: CQRS command and query paths, double-entry ledger, saga with compensation, transactional outbox, event feed |
| `audit-ai-service` | Python 3.12, FastAPI, E2A, DSA | Audits settlement outcomes: deterministic risk rules, confirmation against the system of record, policy-grounded explanations |

Requirements and the step-by-step guide live in the private hub (`settlement/`). CI: `settlement-ci`.

## Branches and tags

| Branch / tag | Purpose |
|---|---|
| `main` | Framework-consumption build. |
| `spike/v1-original` · tag `spike-v1` | Snapshot of the original spike work. Frozen. |
| `pre-g2c-baseline` | Earlier baseline (Spring Boot skeletons). Frozen. |
| `aidlc/settlement-service`, `aidlc/audit-ai-service` | The untouched AI-DLC scaffolds, as generated. |

## Framework family

[E2A](https://github.com/subhamviky/e2a-framework) ·
[A2C · G2C · P0](https://github.com/subhamviky/a2c-framework) ·
[Capability-Oriented DSA](https://github.com/subhamviky/capability-oriented-dsa)