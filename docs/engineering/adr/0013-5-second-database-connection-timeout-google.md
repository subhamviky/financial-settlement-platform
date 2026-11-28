# ADR 0013: A 5-second database connection timeout on Google Cloud

- **Status:** Accepted
- **Date:** 2026-11 (Phase B)
- **Deciders:** repository owner

## Context

Drill 1 showed requests waiting 30 s for a connection while the ledger VM was stopped, so health probes saw no answer.

## Options considered

A) Keep 30 s. B) `application-gcp.yml` sets the pool's `connection-timeout` to 5000 ms (`GcpPoolSettingsTests`).

## Decision

B. Drill 1 is to be repeated to measure the effect.

## Consequences

A fast 5xx the callers can handle; a stall between 5 and 30 s now fails requests it would have survived.
