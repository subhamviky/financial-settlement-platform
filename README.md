# Cloud-Native Financial Settlement Platform

> The system of record of the Order-to-Cash landscape: money moves between ledger accounts
> exactly once, the ledger is always balanced, and every outcome is announced as an event.
> An AI audit service explains risky settlements, but never decides or moves money.

## From spike to frameworks to project

This repository went through three stages. Each one is kept, so the path can be followed.

| Stage | Where | What happened |
|---|---|---|
| **1. Architectural spike** | branch [`spike/v1-original`](https://github.com/subhamviky/financial-settlement-platform/tree/spike/v1-original) (tag `spike-v1`), earlier [`pre-g2c-baseline`](https://github.com/subhamviky/financial-settlement-platform/tree/pre-g2c-baseline) | Hands-on test of whether ledger-grade patterns (saga, idempotency, double entry, events) carry over to cloud-native Java services. They did, but every service re-implemented the same controls by hand. |
| **2. Frameworks** | [E2A](https://github.com/subhamviky/e2a-framework) · [A2C · G2C · P0](https://github.com/subhamviky/a2c-framework) · [Capability-Oriented DSA](https://github.com/subhamviky/capability-oriented-dsa) | The recurring controls became framework contracts: E2A's Deterministic CQRS profile and agent lifecycle, generation and scaffolding (G2C, P0, A2C), and the governed build pipeline (AI-DLC). |
| **3. Completed project** | **`main`** (this branch) | The spike rebuilt by **consuming** those frameworks: both services scaffolded by the AI-DLC pipeline from one request file each, domain code written on the framework contracts. |

### What changed between the spike and `main`

| In the spike | In `main` | Framework that made the difference |
|---|---|---|
| Three Java services (orchestrator, ledger, audit) | One Java service with explicit command, query and saga paths, plus a Python audit service | E2A Deterministic CQRS profile (`BaseOrchestrator`, `BaseCommandService`, `BaseQueryService`), from e2a-java (E2A `v1.3.0`) |
| Kafka for events | Transactional outbox and a pull feed with a versioned JSON-Schema contract, checked in CI against the events the tests really produce | E2A event path; contract-first |
| Idempotency built per service | Atomic claim per (tenant, key); a replay returns the stored result, a reused key with other content is `409` | E2A claim states |
| AI audit with Spring AI inside the Java code | Python E2A agent with policy retrieval; the rules decide, the agent explains, the system of record confirms | E2A `BaseAgent`, `BaseRAGPipeline`; DSA `LOWER_BOUND` for risk bands |
| Services and infrastructure written by hand | Generated from request files: Maven/Poetry projects, base classes, agent skeletons, Azure Terraform, NFR modules | AI-DLC pipeline (P0 + G2C + A2C) |

## Google Cloud (free tier) — hub design note GCP-01

The platform runs on Google Cloud next to Azure. Configuration picks the cloud; the code is the same:

| | Azure (`*/infra`) | Google Cloud (`*/infra/gcp`) |
|---|---|---|
| Ledger (PostgreSQL) | Azure Database for PostgreSQL | PostgreSQL in a container on an Always Free e2-micro VM: no external IP, password from Secret Manager at boot, daily `pg_dump` to Cloud Storage |
| settlement-service | Container Apps | Cloud Run (scale to zero), Direct VPC egress to the ledger VM |
| Outbox relay | background timer | after each mutating request (`RelayAfterRequestFilter`), plus Cloud Scheduler every 10 minutes → `POST /internal/jobs/maintenance` (relay and stale-claim recovery) |
| Settlement events | Service Bus queue | Pub/Sub topic `settlement-events` (`PubSubEventPublisher`) |
| audit-ai-service state | Cosmos DB | Firestore (`AUDIT_STATE_BACKEND=firestore`, collection `audit`) |
| audit-ai-service events | Service Bus consumer | Pub/Sub push → `POST /pubsub/settlement-events` (`PubSubPushDelivery`, E2A v1.10.0), dead letters to `audit-settlement-events-dlq` |
| Who may call | Container Apps authentication (Entra ID) | Cloud Run IAM (`roles/run.invoker`; neither service is public) verifies the caller's Google ID token, and the services read it (`E2A_IDENTITY_SOURCE=gcp_cloud_run`, E2A v1.11.0): the deployment grants each caller its tenants and roles (`identity_subject_roles`); audit-ai reads the event feed with its own ID token |
| audit-ai model | E2A's simulated model | simulated, or Gemini through the Gemini Developer API's free tier (`E2A_PROVIDER_PROFILE=gemini_api`; the image built with `--build-arg POETRY_EXTRAS=gemini`) |

Cloud Run gives a service CPU only while it handles a request, so nothing in it runs on a timer. That is why the relay runs after each request and on the scheduled call. settlement-service runs with `SPRING_PROFILES_ACTIVE=prod,gcp`. The values for a deployment are placeholders in [`deploy/gcp/binding.vars`](deploy/gcp/binding.vars) until the cloud account exists. CI tests the Pub/Sub publisher on the Pub/Sub emulator, runs audit-ai-service's tests again on the Firestore emulator, and plans both `infra/gcp` stacks against a mock provider.

## The project

| Service | Stack | Role |
|---|---|---|
| `settlement-service` | Java 21, Spring Boot 3.5, PostgreSQL (H2 locally) | CQRS command and query paths, double-entry ledger, saga with compensation, transactional outbox, event feed, ledger adjustments (write-offs and refunds) |
| `audit-ai-service` | Python 3.12, FastAPI, E2A, DSA | Audits settlement outcomes: deterministic risk rules, confirmation against the system of record, policy-grounded explanations |

Infrastructure: Azure, as Terraform only (Container Apps, Service Bus, Key Vault, PostgreSQL Flexible Server), tested against mock providers.

**Its place in the landscape:** [order-to-cash-agentic-ai](https://github.com/subhamviky/order-to-cash-agentic-ai) submits settlements here; [payment-reconciliation-engine](https://github.com/subhamviky/payment-reconciliation-engine) reads the event feed, matches the bank statement against it, and posts accepted write-offs and refunds back to the ledger.

## Design

The full HLD and LLD of both services, with diagrams and a PDF copy, is in [`docs/design/`](docs/design/SETTLEMENT_PLATFORM_DESIGN.md).

## Evidence

Verified in CI (`settlement-ci`) on every push: the Java and Python test suites, the event contract against real events, and Terraform `fmt`/`validate`/`test`. The three projects also run together in the `landscape-demo` workflow of order-to-cash-agentic-ai. This is an architecture reference implementation: it has not been deployed or load-tested, and it makes no production claims.

## Branches and tags

| Branch / tag | Purpose |
|---|---|
| `main` | The completed project, built on the framework family. |
| `spike/v1-original` · tag `spike-v1` | The architectural spike, frozen. `spike-v1` is the untouched snapshot. |
| `pre-g2c-baseline` | An earlier step of the spike (Spring Boot skeletons). Frozen. |
| `aidlc/settlement-service`, `aidlc/audit-ai-service` | The scaffolds exactly as the AI-DLC pipeline generated them. |
