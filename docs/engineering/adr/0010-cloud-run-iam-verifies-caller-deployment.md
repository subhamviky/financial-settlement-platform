# ADR 0010: Cloud Run IAM verifies the caller; the deployment grants tenants and roles

- **Status:** Accepted
- **Date:** 2026-11 (SI-01, GCP-01)
- **Deciders:** repository owner

## Context

The service had trusted `X-Tenant-Id` with public ingress and no authentication (gap G-1).

## Options considered

A) Keep header trust. B) Verified caller identity at ingress; the token names only an email; the deployment grants each caller its tenants and actions (`tenant:<id>`, roles).

## Decision

B (`E2A_IDENTITY_SOURCE=gcp_cloud_run`). Neither service is public; `/actuator/health` and the audit service's `/health` need no identity.

## Consequences

Fail-closed identity with no secret to leak. Callers must be listed in the grants.
