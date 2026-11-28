# ADR 0004: An append-only, balanced ledger; compensation by new entries

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

Money records must be auditable and reversible without rewriting history.

## Options considered

A) Update balances in place. B) Append-only entries, a debit and a credit of the same amount for every movement, reversals as new entries.

## Decision

B (invariants I-2 and I-3). `LedgerService` always writes both sides; `isBalanced` is checkable; the cloud scenario's step 8 checks the ledger is balanced after each run.

## Consequences

Full history and a balance that can be proved. Storage grows; no deletes exist, so an erroneous posting is compensated, not removed.
