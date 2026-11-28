# ADR 0007: PostgreSQL in a container on an Always Free e2-micro VM for the ledger on Google Cloud

- **Status:** Accepted
- **Date:** 2026-11 (GCP-01)
- **Deciders:** repository owner

## Context

The ledger needs relational constraints and transactions; Cloud SQL has no free tier.

## Options considered

A) Cloud SQL. B) Firestore. C) PostgreSQL in a container on an Always Free e2-micro with no external address, password from Secret Manager at boot, daily `pg_dump` to Cloud Storage.

## Decision

C. Cloud Run reaches it over Direct VPC egress; the VM's account can create backup objects but not read or delete them; operators use IAP.

## Consequences

Zero cost and real relational semantics. The VM is a single point of failure with one disk (G-11): recovery measured at 40 s for a stopped VM and a restore of 54 settlements in 31 s, with an RPO of up to 24 hours.
