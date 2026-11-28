# Onboarding

Read: `README.md`, the [solution overview](solution-overview.md), the design document sections 4 (invariants), 8 and 10 (failure table), then the [ADRs](../adr/README.md) 1, 2, 3, 6.

Run locally:

```bash
cd settlement-service && mvn -q verify          # Java 21; H2 in PostgreSQL mode; emulators for publishers via docker compose
cd ../audit-ai-service && poetry install && poetry run pytest -q
```

Map: `settlement-service/src/main/java/com/subhamviky/settlement/` (`api`, command, query and saga services, `ledger`, `outbox`), `audit-ai-service/src/audit_ai_service/` (`agents`, `routers`, `services`, `settlement_client.py`), `contracts/` (events and tools), `*/infra` (Azure) and `*/infra/gcp` (Google Cloud), `landscape-identity/` (identity grants).

Working agreements: one concern per branch; CI green to merge; a regression test for every bug; an ADR for decisions you would have to explain later; never real data; the deploy and scenario workflows are in the assistant repository ([release process](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/release-process.md)).
