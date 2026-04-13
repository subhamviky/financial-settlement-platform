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
