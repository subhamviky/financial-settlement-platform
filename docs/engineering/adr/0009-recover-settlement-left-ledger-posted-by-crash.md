# ADR 0009: Recover a settlement left LEDGER_POSTED by a crash (StuckSettlementRecovery)

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

A crash between the ledger posting (T1) and the clearing outcome (T2 or T3) left a settlement neither completed nor compensated.

## Options considered

A) Manual repair. B) A recovery job that finds settlements stuck longer than `stale-after`, asks clearing again, and completes or compensates them with one outcome and one event.

## Decision

B (was gap G-2). It relies on the clearing port answering the same for the same settlement id, and it completes the client's idempotency record.

## Consequences

One outcome, one event, whichever of the crashed request, a retry or the recovery finishes first (the loser sees the recorded outcome).
