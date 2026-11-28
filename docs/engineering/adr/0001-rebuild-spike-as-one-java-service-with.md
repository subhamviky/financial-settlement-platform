# ADR 0001: Rebuild the spike as one Java service with explicit command, query and saga paths, plus a Python audit service

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

The architectural spike had three Java services (orchestrator, ledger, audit) with Kafka and Spring AI. It proved the idea but repeated the same controls in each service.

## Options considered

A) Keep three services. B) One Java service on E2A's deterministic CQRS profile (`BaseOrchestrator`, `BaseCommandService`, `BaseQueryService`) and a separate Python audit service on E2A's agent runtime.

## Decision

B. The ledger, the saga and the event feed are one deployable; the AI audit is separate so that a model failure can never touch the ledger.

## Consequences

Fewer moving parts and one place for idempotency, outbox and recovery. The audit service can fail, degrade or be redeployed without affecting money.
