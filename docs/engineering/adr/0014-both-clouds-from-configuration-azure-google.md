# ADR 0014: Both clouds from configuration: Azure and Google Cloud share the code

- **Status:** Accepted
- **Date:** 2026-11 (GCP-01)
- **Deciders:** repository owner

## Context

The platform was first built for Azure (Container Apps, Service Bus, Cosmos DB) and later run on Google Cloud's free tier.

## Options considered

A) A fork per cloud. B) One codebase; profiles and Terraform per cloud (`*/infra`, `*/infra/gcp`); state and delivery through E2A ports.

## Decision

B. CI tests the Azure and Google Cloud state bindings (Cosmos DB and Firestore emulators) and event publishers (Service Bus and Pub/Sub emulators).

## Consequences

No divergence between clouds; the Azure deployment was never run live (stated in the design).
