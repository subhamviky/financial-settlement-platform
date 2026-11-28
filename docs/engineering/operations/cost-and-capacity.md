# Cost and capacity: the ledger and the audit service

Zero by design on the free tier ([platform note](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/cost-and-capacity.md)). Resources of this repository: Cloud Run services `settlement-service` and `audit-ai-service` (scale to zero, never public); one Always Free e2-micro VM (`ledger-db`, no external address); a Cloud Storage backup bucket; Pub/Sub topic `settlement-events` with a push subscription and a dead-letter subscription; Firestore collection `audit`; one Cloud Scheduler job (settlement maintenance); Secret Manager secrets (the database password; the Gemini key for the optional model).

What breaks first: the e2-micro's 1 GB of memory (database size and connections); cold starts (the first request waits for an instance and, for settlement, a connection within 5 s); the Scheduler's three-job account limit (the landscape uses all three); the Gemini free-tier quota for audit explanations (the service degrades to rule-only explanations).

Not measured: throughput under load.
