# ADR 0008: Settlement events go to a Pub/Sub topic; audit-ai receives them by push with a dead-letter subscription

- **Status:** Accepted
- **Date:** 2026-11 (GCP-01)
- **Deciders:** repository owner

## Context

On Cloud Run there is no always-on consumer; events must arrive when the service is scaled to zero.

## Options considered

A) A polling consumer. B) Pub/Sub push to `POST /pubsub/settlement-events` with a dead-letter subscription after 5 attempts; the audit claim per `event_id` makes redelivery a no-op.

## Decision

B (`PubSubPushDelivery`, E2A v1.10.0).

## Consequences

Delivery works with scale-to-zero. An interruption costs delay, not loss: events wait 24 hours, 10 s to first delivery after a restore (drill 2).
