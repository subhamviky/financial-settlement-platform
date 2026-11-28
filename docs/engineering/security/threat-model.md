# Threat model: settlement-service and audit-ai-service

- **Status:** Draft 2026-11-28, awaiting owner review · STRIDE per boundary · The platform-wide model, boundaries and residual risks are in the [assistant repository](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/security/threat-model.md); this page adds what is specific to the ledger and the audit service. Synthetic data only.

## Assets

The ledger (balanced, append-only), idempotency records, the outbox and event feed, audit findings, the ledger VM and its backups, the database password.

## Threats and controls

| # | Threat | Control | Evidence |
|---|---|---|---|
| S1 | **Spoofing** a caller or a tenant | Cloud Run IAM verifies the Google ID token; the deployment grants tenants and actions; fail-closed; neither service is public | `application` identity filter; SI-01 |
| S2 | **Tampering**: a duplicate or replayed request moves money twice | atomic claim per (tenant, key); replay returns the stored result; same key with different content is 409 | invariant I-1, tests |
| S3 | **Tampering**: an unbalanced or edited ledger | append-only entries; debit and credit written together; balance checkable (`isBalanced`) | I-2, I-3, scenario step 8 |
| S4 | **Tampering**: an adjustment beyond the limit, or two racing | row lock with forced version increment; check inside the transaction | I-7, tests |
| S5 | **Tampering**: an event announces a change that did not commit, or vice versa | transactional outbox | I-5 |
| S6 | **Tampering**: the model decides or moves money | the audit AI only explains; rules decide; a flag needs confirmation from the system of record | I-9 |
| S7 | **Repudiation** | every movement has an event, an idempotency key and a correlation id; one JSON log line per request | design section 10 |
| S8 | **Information disclosure**: cross-tenant read | tenant on every query and key; identity grants | design 5.6 |
| S9 | **Information disclosure**: backups or the database password | password in Secret Manager read at boot; backup bucket writable by the VM's account but not readable or deletable; operators reach the VM only over IAP; no external address | drill 3, platform review 0007 |
| S10 | **Denial of service**: a slow or stopped database holds capacity | 5 s connection timeout; Cloud Run's request limits; readiness probe keeps bad instances out of service | ADR 0012, drill 1 |
| S11 | **Denial of service**: a poison event blocks audit | dead-letter after 5 attempts; `reconcile` confirms or replays | design section 10 |
| S12 | **Elevation of privilege**: the model's text injected into the audit explanation | retrieved policy text is data; groundedness check; rule-only fallback | audit-ai tests, evals |

## Residual risks

Single VM and disk (G-11); `VelocityWindow` is per replica (G-3); two replicas can send the same event (G-4, consumers de-duplicate); `outbox_event.payload` is `varchar(4000)` (G-7); the Azure identity path (Entra) was never run live; no penetration test.
