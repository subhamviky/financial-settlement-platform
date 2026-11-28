# ADR 0012: A retry and circuit breaker around audit-ai's reads of settlement-service

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

The audit client had no retry or breaker (G-8): an outage would hang or hammer.

## Options considered

A) None. B) Retry reads on transport failures and 5xx with jittered backoff and a retry budget, never on 4xx, behind a circuit breaker that refuses at once during an outage.

## Decision

B (`settlement_client.py`).

## Consequences

Fast, typed failures during an outage; the message is redelivered and the claim holds.
