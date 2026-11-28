# Architecture Decision Records

One record per significant decision (context, options, decision, consequences). Records are not edited to change history: a reversed decision gets a new record that supersedes the old one. These are the decisions of `settlement-service` and `audit-ai-service`. Platform-wide decisions (free tier, synthetic data, Workload Identity Federation, promotion, the documentation layout) are in the [assistant repository's ADR index](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/adr/README.md).

Template: [`0000-template.md`](0000-template.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-rebuild-spike-as-one-java-service-with.md) | Rebuild the spike as one Java service with explicit command, query and saga paths, plus a Python audit service | Accepted |
| [0002](0002-transactional-outbox-pull-feed-with-versioned.md) | A transactional outbox and a pull feed with a versioned JSON-Schema contract, not Kafka | Accepted |
| [0003](0003-idempotency-by-atomic-claim-per-tenant-key.md) | Idempotency by an atomic claim per (tenant, key) | Accepted |
| [0004](0004-append-only-balanced-ledger-compensation-by.md) | An append-only, balanced ledger; compensation by new entries | Accepted |
| [0005](0005-limits-adjustments-enforced-inside-locked.md) | Limits on adjustments enforced inside the locked transaction (forced version increment) | Accepted |
| [0006](0006-rules-decide-agent-explains-system-record.md) | The rules decide, the agent explains, the system of record confirms (the audit AI never decides or moves money) | Accepted |
| [0007](0007-postgresql-container-always-free-e2-micro-vm.md) | PostgreSQL in a container on an Always Free e2-micro VM for the ledger on Google Cloud | Accepted |
| [0008](0008-settlement-events-go-pub-sub-topic-audit-ai.md) | Settlement events go to a Pub/Sub topic; audit-ai receives them by push with a dead-letter subscription | Accepted |
| [0009](0009-recover-settlement-left-ledger-posted-by-crash.md) | Recover a settlement left LEDGER_POSTED by a crash (StuckSettlementRecovery) | Accepted |
| [0010](0010-cloud-run-iam-verifies-caller-deployment.md) | Cloud Run IAM verifies the caller; the deployment grants tenants and roles | Accepted |
| [0011](0011-outbox-relay-after-each-mutating-request-plus.md) | Outbox relay after each mutating request plus a Cloud Scheduler maintenance call | Accepted |
| [0012](0012-retry-circuit-breaker-around-audit-ai-reads.md) | A retry and circuit breaker around audit-ai's reads of settlement-service | Accepted |
| [0013](0013-5-second-database-connection-timeout-google.md) | A 5-second database connection timeout on Google Cloud | Accepted |
| [0014](0014-both-clouds-from-configuration-azure-google.md) | Both clouds from configuration: Azure and Google Cloud share the code | Accepted |
