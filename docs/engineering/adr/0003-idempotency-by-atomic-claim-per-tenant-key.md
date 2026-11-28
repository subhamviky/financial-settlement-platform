# ADR 0003: Idempotency by an atomic claim per (tenant, key)

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

A client retry after a timeout must not move money twice.

## Options considered

A) A check-then-insert. B) The primary key of `idempotency_record` as an atomic insert claim; a replay returns the stored result; the same key with different content is 409.

## Decision

B (invariant I-1). Recovery completes the client's idempotency record, so a retry of a crashed request replays the recovered outcome.

## Consequences

At-most-once execution without a lock manager. A request in progress returns 409 to a second caller; callers must retry with the same key.
