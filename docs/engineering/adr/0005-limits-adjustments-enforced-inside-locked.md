# ADR 0005: Limits on adjustments enforced inside the locked transaction (forced version increment)

- **Status:** Accepted
- **Date:** 2026-11 (backlog: write-offs and refunds)
- **Deciders:** repository owner

## Context

Write-offs on a settlement must total at most the limit and refunds at most its amount, even when two adjustments arrive together.

## Options considered

A) Check before the transaction. B) Take a row lock with a forced version increment on the settlement, then check and write in one transaction.

## Decision

B (invariant I-7). Two concurrent adjustments: one commits, the other gets 409; an over-limit adjustment gets 422 and rolls back.

## Consequences

No race can pass the limit. Adjustments on one settlement are serialised.
