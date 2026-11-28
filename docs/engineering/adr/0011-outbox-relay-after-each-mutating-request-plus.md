# ADR 0011: Outbox relay after each mutating request plus a Cloud Scheduler maintenance call

- **Status:** Accepted
- **Date:** 2026-11 (GCP-01)
- **Deciders:** repository owner

## Context

A background timer does not run when Cloud Run is scaled to zero.

## Options considered

A) minReplicas 1. B) Relay after each mutating request (`RelayAfterRequestFilter`) and every 10 minutes `POST /internal/jobs/maintenance` (relay and stale-claim recovery) from Cloud Scheduler.

## Decision

B, using a Cloud Scheduler job.

## Consequences

Events are published promptly after a change and swept on a schedule; with no traffic they wait at most ten minutes (G-9).
