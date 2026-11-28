# ADR 0002: A transactional outbox and a pull feed with a versioned JSON-Schema contract, not Kafka

- **Status:** Accepted
- **Date:** 2026 (completed project)
- **Deciders:** repository owner

## Context

State changes and their events must commit together; consumers live in other clouds and other repositories.

## Options considered

A) Kafka. B) Publish directly after the commit. C) Outbox written in the same transaction, a relay to Service Bus or Pub/Sub, and a pull feed (`GET /api/v1/events`) with a versioned JSON-Schema contract checked in CI against the events the tests really produce.

## Decision

C. No state change without its event and no event without its change (invariant I-5). Delivery is at least once; consumers de-duplicate by `event_id`.

## Consequences

Consumers (audit-ai, reconciliation) can pull or receive pushes. Duplicates are possible under scale-out (G-4); consumers must be idempotent, and are.
