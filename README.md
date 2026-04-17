# Cloud-Native Financial Settlement Platform

> **Java 21 · Spring Boot 3.x · Kafka · Redis · PostgreSQL · Spring AI + RAG · AWS EKS**

A production-realistic financial settlement platform that translates **SAP BOPF architecture** into cloud-native Java microservices on AWS — demonstrating Staff-level engineering capability across distributed systems design, event-driven architecture, and AI-powered compliance.

[![Build](https://github.com/subhamviky/financial-settlement-platform/actions/workflows/deploy.yml/badge.svg)](https://github.com/subhamviky/financial-settlement-platform/actions)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)]()
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?logo=spring)]()
[![AWS](https://img.shields.io/badge/AWS-EKS%20%7C%20RDS%20%7C%20SQS-FF9900?logo=amazonaws)]()

---

## Architecture

```
 Client
   │  POST /api/v1/settlements
   │  X-Idempotency-Key: <uuid>
   ▼
 Settlement Orchestrator  (port 8081)
   │  @Idempotent AOP  →  Redis SETNX check (24h TTL)
   │  SagaOrchestrator.start()
   │       ├──▶ LedgerDebitStep   →  Kafka: settlement.initiated
   │       ├──▶ LedgerCreditStep
   │       └──▶ NotificationStep
   │  state → COMPLETED
   │  Kafka: settlement.completed
   │
   ├──────────────────────────────────────────────────┐
   ▼                                                  ▼
 Ledger Service  (port 8082)             Audit-AI Service  (port 8083)
   Kafka consumer (manual ack + DLT)       Kafka consumer
   Double-entry DB write                   Spring AI RAG pipeline
   Idempotency guard (UNIQUE index)        ChromaDB policy retrieval
   Reversal on compensation                LLM anomaly detection (GPT-4o / Ollama)
                                           AuditLog → PostgreSQL + S3 archive
```

### Settlement State Machine

```
INITIATED → LEDGER_PENDING → LEDGER_UPDATED → COMPLETED
                 │                  │
                 └────── FAIL ───────┘
                              ▼
                        COMPENSATING → COMPENSATED
```

---

## BOPF → Cloud-Native Mapping

| SAP BOPF Concept | Microservice | Implementation |
|---|---|---|
| Settlement BO | Settlement Orchestrator | Saga engine · `@Idempotent` AOP · Redis idempotency |
| Ledger BO | Ledger Service | Double-entry posting · Kafka consumer · reversal entries |
| Audit BO | Audit-AI Service | Spring AI RAG · LLM inference · S3 archive |
| Action Framework | `SagaStep` interface | `execute()` + `compensate()` contract |
| Determination (guard) | `SettlementStateTransitions` | Illegal transitions throw at runtime |
| Cross-BO Event Framework | Kafka topics + `MessagingPort` | `settlement.{initiated,completed,failed,compensated}` |
| Lock Management | `@Idempotent` + Redis SETNX | Atomic check-and-set prevents concurrent duplicates |

---

## Architecture Philosophy — Correct by Design
 
> *Idempotency and reconciliation are business features, not just technical safeguards.*
 
Every architectural decision in this platform is grounded in a principle proven at $350M+ SAP TM
financial scale: **the system must be incapable of incorrect states by design**, not merely
defended against them by runtime guards.

## Why This Domain Justifies This Level of Control
 
The architectural controls in this platform — Saga orchestration, `@Idempotent` AOP,
double-entry DB invariant, business-status gates, RAG-powered dispute audit — are not
default choices applied to every payment system. They are **proportional responses**
to the complexity of the domain this platform models.
 
### The Complexity Justification
 
This platform is modelled on SAP TM Charge Management, which presents three conditions
that together mandate business-layer financial integrity controls:
 
**1. Input mutability** — charges are not fixed at creation.
Rate tables, calculation rules, and agreement sheets determine amounts dynamically.
Carrier invoices can be revised after initial submission.
A revised amount for the same charge must be treated as a valid update,
not a duplicate — which requires a *business identity key* (analogous to `REF_ELEM_KEY`),
not a technical hash.
 
**2. Output ambiguity** — the same charge type and amount can carry different business meaning
depending on calculation level and resolution level.
Infrastructure-layer deduplication (by payload hash) would silently discard valid,
distinct charges. The system must understand *context*, not just *value*.
 
**3. Regulatory and audit exposure** — incorrect postings affect vendor payments,
regulatory reporting, and carrier-shipper contractual obligations.
The cost of a wrong post is high enough to justify the engineering investment
in immutable ledger design, compensation handlers, and AI-powered compliance audit.
 
### When These Controls Would Be Over-Engineering
 
A standard SaaS subscription billing flow — fixed monthly amount, same account,
no mid-flight revisions — does not need any of this.
A standard API-layer idempotency key and infrastructure-level message deduplication
are sufficient. Adding Saga orchestration and double-entry ledger constraints
to that flow would increase latency, operational complexity, and testing surface area
with no business benefit.
 
The rule: **match control weight to domain complexity.**
 
| Factor | This Platform | Simple Payment Flow |
|---|---|---|
| Input mutability | High — rates, tables, revised invoices | None — fixed amount |
| Output ambiguity | High — same charge type, different meaning by level | None |
| Audit exposure | High — vendor payments, regulatory | Low |
| **Appropriate control layer** | **Business logic** | **API / infrastructure** |
| Saga + compensation | ✅ Justified | ❌ Over-engineering |
| @Idempotent AOP | ✅ Justified | ❌ API-key header is enough |
| Double-entry DB invariant | ✅ Justified | ❌ Standard unique constraint is enough |
| RAG-powered audit | ✅ Justified | ❌ Operational overhead with no ROI |
 
> *The engineering choices in this repo are deliberate, not default.*
> *They exist because the domain demands them.*
 
### The SAP TM Origin
 
In SAP Transportation Management, financial integrity is enforced structurally:
 
**Line-Element Key** creates a deterministic one-to-one mapping between every
source charge and its settlement posting. When a carrier revises an invoice amount, the system
routes it as a valid business update — not a duplicate — because the key is a business identity,
not a technical dedup hash.
 
**"Completely Invoiced" status gate** means the Finance Ledger cannot receive a posting until the
business has explicitly confirmed the invoice is final. The ledger is immutable by architectural
contract, not by a `try/catch` block.
 
**Dispute Management** is a first-class business workflow that mediates charge deltas and unblocks
final postings — not an error handler, not a retry loop. Discrepancies are expected; the
architecture provides a governed path to resolve them.
 
### How This Platform Implements the Same Principles
 
| SAP TM Principle | This Platform's Implementation |
|---|---|
| Line-Element Key — deterministic business identity | `X-Idempotency-Key` header + `@Idempotent` AOP with Redis `SETNX` — the key is supplied by the business (client), not generated by infrastructure |
| "Completely Invoiced" gate — business status blocks ledger write | `SettlementState.COMPLETED` is the **only** state from which ledger finalisation can occur; `SettlementStateTransitions` guards reject all other paths at runtime |
| Revised amount = valid update, not duplicate error | Idempotency cache returns the **stored response** for a known key — revised retry with same key gets the original result, not a conflict error |
| Dispute as first-class reconciliation workflow | `Audit-AI Service`: Spring AI RAG pipeline retrieves policy context and the LLM reasons about whether the transaction is compliant — anomalies enter a structured review path, not a dead-letter queue |
| Ledger immutability by contract | `UNIQUE INDEX` on `(settlement_id, direction, entry_type)` — the database enforces the double-entry invariant. Corrections create **reversal entries**, never updates |
| Compensation as business rollback, not error recovery | Every `SagaStep` implements `compensate()` — compensation is a designed business operation (reverse the posting), not a catch block |
 
The outcome: every settlement either completes correctly or reaches a governed compensated state.
There is no "partially posted" or "ambiguously settled" condition.
 
**Correct by Design** — regardless of the underlying technology stack.


## Key Engineering Decisions

### 1. Idempotency via AOP — Zero Business Logic Pollution

```java
@Idempotent(
    keyExpression = "#request.idempotencyKey",
    responseType  = SettlementResponse.class
)
@Transactional
public SettlementResponse initiateSettlement(SettlementRequest request) {
    // AOP intercepts BEFORE this executes:
    //   → Redis SETNX check (cache hit = return stored response, skip logic)
    //   → Cache miss = proceed, then store result in Redis with 24h TTL
}
```

Redis `SETNX` + TTL. Atomic — race-condition safe. SpEL key expression keeps annotation reusable across any method.

### 2. Saga with Compensation

Each `SagaStep` implements both `execute()` and `compensate()`. The `SagaOrchestrator` runs steps forward; on any `SagaStepException`, runs compensations in **reverse order**. Both methods must be **idempotent** — at-least-once delivery means they may be called more than once.

```java
public interface SagaStep {
    void execute(Settlement settlement);    // Forward action
    void compensate(Settlement settlement); // Undo — must be idempotent
    String stepName();
}
```

### 3. Double-Entry Ledger — Enforced at DB Level

```sql
-- One DEBIT + one CREDIT per settlement — no application-layer enforcement needed
CREATE UNIQUE INDEX idx_ledger_dedup
    ON ledger_entries(settlement_id, direction, entry_type)
    WHERE entry_type = 'SETTLEMENT';
```

Reversal entries carry `reversal_of_entry_id` — full immutable audit chain. Application-layer idempotency guard checks `existsBySettlementIdAndDirectionAndEntryType()` before every write.

### 4. Event Hardening

```
Producer:  acks=all · enable.idempotence=true · max.in.flight=1
Consumer:  manual ack · ExponentialBackOff(1s → 2s → 4s, max 3 retries)
           → DeadLetterPublishingRecoverer → <topic>.DLT
           + Redis ProcessedEventStore SETNX (consumer-side dedup)
```

`MessagingPort` interface decouples business logic from Kafka — swap to SQS FIFO via `@Profile("aws")` with zero business code changes.

### 5. RAG Audit Pipeline

```
SettlementCompletedEvent
  → embed transaction description (OpenAI text-embedding-3-small / nomic-embed-text)
  → ChromaDB similarity search  →  top-3 policy chunks
  → LLM prompt: system(compliance officer) + user(transaction + policy context)
  → JSON response: { "anomaly": bool, "reason": "...", "severity": "LOW|MEDIUM|HIGH" }
  → AuditLog persisted to PostgreSQL
  → anomaly=true → S3 archive at audits/{date}/anomalies/{settlementId}.json
```

Supports both **OpenAI GPT-4o** (via API key) and **Ollama / Mistral** (local, zero cost).

---

## Module Structure

```
financial-settlement-platform/
├── pom.xml                          ← Root (parent POM, Spring Boot 3.2.x)
├── common-lib/                      ← Shared: DTOs, exceptions, events, @Idempotent annotation,
│                                       CorrelationIdUtil, MessagingPort, ProcessedEventStore
├── settlement-orchestrator/         ← Port 8081: REST API, saga engine, Redis idempotency
│   ├── domain/                      ← Settlement entity, SettlementState enum
│   ├── saga/                        ← SagaOrchestrator, SagaStep, step implementations
│   ├── idempotency/                 ← @Idempotent annotation + AOP aspect
│   └── event/                       ← SettlementEventPublisher (Kafka / SQS adapter)
├── ledger-service/                  ← Port 8082: double-entry posting, Kafka consumer
│   ├── domain/                      ← LedgerEntry, EntryDirection
│   ├── consumer/                    ← LedgerKafkaConsumer (manual ack + DLT)
│   └── service/                     ← LedgerPostingService (idempotent write + reversal)
├── audit-ai-service/                ← Port 8083: Spring AI + RAG, LLM audit
│   ├── rag/                         ← PolicyDocumentLoader, AuditRagService
│   ├── consumer/                    ← AuditKafkaConsumer
│   └── service/                     ← AuditArchiveService (S3, @Profile("aws"))
├── api-gateway/                     ← JWT auth, routing, correlation ID injection
└── docker/
    ├── docker-compose.yml           ← PostgreSQL · Redis · Kafka · Zookeeper · ChromaDB
    │                                   · Jaeger · Prometheus · Grafana
    └── postgres-init/               ← Auto-creates 3 databases + 3 users on first start
```

---

## Running Locally

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java JDK | 21 | `sdk install java 21.0.3-tem` (SDKMAN) |
| Maven | 3.9+ | `sdk install maven 3.9.6` |
| Docker Desktop | Latest | docker.com |
| VS Code | Latest | with Java Extension Pack + Spring Boot Tools |

### Start

```bash
# 1. Clone
git clone https://github.com/subhamviky/financial-settlement-platform.git
cd financial-settlement-platform

# 2. Start all infrastructure (PostgreSQL, Redis, Kafka, ChromaDB, Jaeger, Prometheus, Grafana)
cd docker && docker compose up -d
cd ..

# 3. Build
mvn clean install -DskipTests

# 4. Run services (3 terminals or VS Code F5 launch configs)
cd settlement-orchestrator && mvn spring-boot:run   # port 8081
cd ledger-service         && mvn spring-boot:run   # port 8082
cd audit-ai-service       && mvn spring-boot:run   # port 8083

# 5. Initiate a settlement
curl -X POST http://localhost:8081/api/v1/settlements \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{
    "sourceAccount": "ACC1234567890",
    "destinationAccount": "ACC0987654321",
    "amount": "1500.00",
    "currency": "USD",
    "settlementType": "TRANSFER"
  }'

# 6. Verify ledger entries (double-entry)
curl http://localhost:8082/api/v1/ledger/settlements/{id}/entries

# 7. Check audit result
curl http://localhost:8083/api/v1/audits/{id}
```

### Observability

| Dashboard | URL |
|---|---|
| Grafana (metrics) | http://localhost:3000  (admin / admin) |
| Prometheus | http://localhost:9090 |
| Jaeger (traces) | http://localhost:16686 |
| Actuator (health) | http://localhost:8081/actuator/health |

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| Language | Java 21 | Records, sealed classes, pattern matching |
| Framework | Spring Boot 3.x | Web, JPA, Actuator, Data Redis |
| Messaging | Apache Kafka | Event streaming, at-least-once delivery |
| Idempotency | Redis (SETNX) | Dedup key store + ProcessedEventStore |
| Database | PostgreSQL 15 | Financial ledger, saga state, audit logs |
| AI / RAG | Spring AI + ChromaDB | Policy ingestion, LLM anomaly detection |
| LLM | GPT-4o (or Ollama/Mistral) | Anomaly classification |
| Observability | OpenTelemetry + Micrometer | Distributed traces, histograms, counters |
| Dashboards | Prometheus + Grafana | Settlement latency, success rate, DLT count |
| Cloud | AWS EKS · RDS · ElastiCache · SQS · S3 | Production deployment |
| CI/CD | GitHub Actions → ECR → kubectl | Build · test · push · deploy |

---

## AWS Deployment

```bash
# Create EKS cluster
eksctl create cluster -f aws/eks/cluster.yaml

# Deploy
kubectl apply -f k8s/
kubectl rollout status deployment/settlement-orchestrator -n settlement

# CI/CD: push to main triggers GitHub Actions
# build → test → ECR push → kubectl set image → rollout status
```

| Component | Local | AWS |
|---|---|---|
| PostgreSQL | Docker container | RDS PostgreSQL 15 |
| Redis | Docker container | ElastiCache Redis 7 |
| Messaging | Kafka + Zookeeper | SQS FIFO + DLQ |
| Containers | Docker Compose | EKS (t3.medium × 2) |
| Logs | Console stdout | CloudWatch Logs |
| Metrics | Prometheus + Grafana | CloudWatch Metrics |
| Traces | Jaeger | AWS X-Ray / CloudWatch |

---

## Portfolio Context

This project is one of three active AWS portfolios validating enterprise distributed-systems expertise in cloud-native form:

| Project | Stack | Focus |
|---|---|---|
| **This repo** — Financial Settlement Platform | Java 21 / Spring Boot | Saga · Idempotency · RAG |
| [Order-to-Cash Agentic AI](https://github.com/subhamviky/order-to-cash-agentic-ai) | Python / LangGraph | Multi-agent · Bedrock · Terraform IaC |
| [Payment Reconciliation Engine](https://github.com/subhamviky/aws-reconciliation-engine) | Python / Lambda | Serverless · DynamoDB · SQS |

---

**Author:** Subham Gupta — Staff Architect, SAP Labs India
[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0077B5?logo=linkedin)](https://linkedin.com/in/subham-gupta-0a05a058)
[![Email](https://img.shields.io/badge/Email-subhamviky@gmail.com-D14836?logo=gmail)](mailto:subhamviky@gmail.com)
