# ADR 0006: The rules decide, the agent explains, the system of record confirms (the audit AI never decides or moves money)

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

An AI audit of settlements is useful but must not become an authority.

## Options considered

A) Let the model flag. B) Deterministic rules produce the verdict and risk band; the model explains with retrieved policy text; a flag requires confirmation by the system of record (`AuditService._confirmed`).

## Decision

B (invariant I-9). An ungrounded model answer (groundedness below 0.5) or a model failure yields a rule-only explanation marked `degraded`.

## Consequences

Auditability and no model-driven action; the cost is that the explanation is only as good as the rules' hits.
