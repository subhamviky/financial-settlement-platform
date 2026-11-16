# Cloud-Native Financial Settlement Platform

> `main` is built by **consuming** the E2A framework family: the AI-DLC pipeline scaffolds each
> service (P0 + G2C + A2C), the settlement service realises E2A's Deterministic CQRS profile in
> Java, and the audit service runs an E2A agent with retrieval over policy clauses.

| Service | Stack | Role |
|---|---|---|
| `settlement-service` | Java 21, Spring Boot 3.5, PostgreSQL (H2 locally) | System of record: CQRS command and query paths, double-entry ledger, saga with compensation, transactional outbox, event feed |
| `audit-ai-service` | Python 3.12, FastAPI, E2A, DSA | Audits settlement outcomes: deterministic risk rules, confirmation against the system of record, policy-grounded explanations |

Requirements and the step-by-step guide live in the private hub (`settlement/`). CI: `settlement-ci`.

## Branches and tags

| Branch / tag | Purpose |
|---|---|
| `main` | Framework-consumption build. |
| `spike/v1-original` · tag `spike-v1` | Snapshot of the original spike work. Frozen. |
| `pre-g2c-baseline` | Earlier baseline (Spring Boot skeletons). Frozen. |
| `aidlc/settlement-service`, `aidlc/audit-ai-service` | The untouched AI-DLC scaffolds, as generated. |

## Framework family

[E2A](https://github.com/subhamviky/e2a-framework) ·
[A2C · G2C · P0](https://github.com/subhamviky/a2c-framework) ·
[Capability-Oriented DSA](https://github.com/subhamviky/capability-oriented-dsa)
## Settlement events on Service Bus (E2A v1.7.x)

settlement-service's outbox relay sends every event to this service's `settlement-events` queue: the event JSON, with the event id as the message id and the idempotency key, and the tenant and event type as application properties. A background worker receives the events, and each one is settled by E2A's `IdempotentConsumer` (`services/consumer.py`) under one claim on its `event_id`. The claim is stored in Cosmos DB, so every replica shares it.

| Path | What happens |
|---|---|
| Queue (normal path) | Audited and acknowledged. A redelivery settles as `duplicate`. An event type this service does not audit is acknowledged without being read. |
| Queue (poison message) | A body that breaks the contract, or a tenant attribute that disagrees with the envelope, goes to the dead-letter subqueue at once, with the reason. |
| `POST /audits/poll` (event feed) | The catch-up path. It settles through the same consumer, so an event that arrives on both paths is audited once. |
| `POST /audits/reconcile` | Settles the dead letters under a lease. A dead letter whose finding already exists is removed. One that never took effect is replayed. A poison one stays for a person to inspect. |
| `POST /audits/consume` | Settles one batch by hand. The worker does this continuously. |

**Configuration:**
- `SERVICEBUS_NAMESPACE` (`<name>.servicebus.windows.net`; the managed identity authenticates, with no secret) or `SERVICEBUS_CONNECTION_STRING` (the emulator).
- `SERVICEBUS_QUEUE` (default `settlement-events`).
- With neither the namespace nor the connection string set, the service uses the event feed only, as before.

**Terraform:** `infra/events.tf` grants settlement-service's identity *Data Sender* on this one queue. One replica always runs, to consume.

## Who may call the API: identity at ingress (E2A v1.7.4)

In Azure, Container Apps authentication with Entra ID verifies every caller and injects the verified claims, in `X-MS-CLIENT-PRINCIPAL`; external requests cannot set that header. The service takes the caller from those claims only (`E2A_IDENTITY_SOURCE=azure_container_apps`). e2a-adapters reads the header, and E2A's `ClaimMapIdentityResolver` resolves it with the Entra ID preset; `E2A_IDENTITY_TENANT_MAP` maps the caller's directory to this deployment's tenant.

| Case | Response |
|---|---|
| No verified claims | 401 |
| Another directory, or a missing `AUDIT_REQUIRED_ROLE` | 403 |

`/health` stays open. Identity is on by default in Terraform (`infra/identity.tf`). Container Apps authentication itself is configured once with `az containerapp auth` (the commands are in that file), because azurerm has no resource for it. Locally and in tests (no source set), the API is open, as before.
