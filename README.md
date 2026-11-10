# Cloud-Native Financial Settlement Platform

> **Status:** `main` is being rebuilt by **consuming** the E2A framework family — P0 scaffolds it,
> G2C generates its E2A class structure, A2C generates its IaC, CI/CD and NFR modules, and the
> E2A AI-DLC pipeline governs the build. The original spike is preserved unchanged (see Branches).

Deterministic system of record: settlement saga, double-entry ledger and transactional outbox (Java CQRS), with a Python audit-AI service.

## Branches and tags

| Branch / tag | Purpose |
|---|---|
| `main` | Framework-consumption build: the service built on, and governed by, the framework family. |
| `spike/v1-original` · tag `spike-v1` | The original architectural spike. It helped discover the invariants that became the frameworks. Frozen. |
| `pre-g2c-baseline` | Earlier baseline (Spring Boot skeletons) from before the G2C rebuild attempt. Frozen. |

## Framework family

[E2A](https://github.com/subhamviky/e2a-framework) ·
[A2C · G2C · P0](https://github.com/subhamviky/a2c-framework) ·
[Capability-Oriented DSA](https://github.com/subhamviky/capability-oriented-dsa)
