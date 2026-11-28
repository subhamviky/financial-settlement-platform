# Test strategy: settlement-service and audit-ai-service

Principles are shared with the [platform strategy](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/testing/test-strategy.md). Layers here:

| Layer | What it proves | Where |
|---|---|---|
| Java unit and service tests | the saga paths, the policy gate, ledger balance, legal state transitions, limits under a row lock, idempotency claims, stuck-settlement recovery, the outbox, the identity filter, the pool settings (`GcpPoolSettingsTests`) | `settlement-service/src/test` (JUnit; H2 in PostgreSQL mode) |
| Publishers on emulators | the relay's Service Bus and Pub/Sub publishers | CI step with emulators |
| Python unit tests (52) | rules, the explanation agent's grounding and fallback, the consumer's claim per event, `reconcile`, push delivery, the settlement client's retry and breaker, correlation middleware | `audit-ai-service/tests` |
| State bindings | the audit tests again with state in Cosmos DB and in Firestore emulators | CI steps |
| Contract | real events from the Java tests satisfy the JSON-Schema contract | CI step with `contract-samples` |
| Conformance and gate | strict E2A conformance; `ProjectReleaseGate` with `evals/golden.json` (every case right) | CI steps |
| Infrastructure | Terraform fmt, validate and test with mock providers (Azure and Google Cloud) | CI |
| Cloud scenario and drills | the whole landscape; the ledger VM stop, the Pub/Sub pause, the restore | `landscape-gcp` in the assistant repository; `docs` drills |

Gaps: no load or soak test; no chaos beyond the drills; the Azure deployment was never run live; `VelocityWindow` is not tested across replicas.
