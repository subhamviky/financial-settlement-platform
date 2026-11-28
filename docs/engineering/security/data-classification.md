# Data classification: settlement-service and audit-ai-service

All data is synthetic ([platform rule](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/security/data-classification.md)). If it were real:

| Data | Where | Classification | Retention here | Protection |
|---|---|---|---|---|
| Ledger accounts, balances, entries, adjustments | PostgreSQL on the e2-micro VM (GCP) or Azure Database for PostgreSQL | Restricted (financial) | until teardown | no external address; password from Secret Manager; append-only |
| Settlements, idempotency records | same database | Restricted | until teardown | tenant-keyed; claim per (tenant, key) |
| Outbox and event feed | same database | Confidential | until published and retained for the feed | tenant on every event |
| Daily backups | Cloud Storage bucket | Restricted | until teardown | VM account create-only; operators restore |
| Audit findings, rule hits, explanations | Firestore (`audit`) or Cosmos DB | Confidential | until teardown | tenant-scoped; explanations are model text over policy documents |
| Policy documents for the audit RAG | files in the image | Internal | with the image | read-only |
| Logs | Cloud Logging | Internal | default retention | one JSON line per request with ids and statuses |

Handling rules: no real data; model requests carry settlement facts only because they are synthetic (a real deployment needs a provider contract); masking of account numbers belongs in the consuming assistant's output.
