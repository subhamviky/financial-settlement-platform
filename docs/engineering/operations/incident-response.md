# Incident response: settlement-service and audit-ai-service

Severity levels, roles and the first ten minutes are platform-wide: see [incident response](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/incident-response.md). Symptoms specific to this repository:

| Symptom | Likely cause | Check | Action |
|---|---|---|---|
| `/actuator/health` returns `000` or 5xx | the ledger VM is stopped or its database is not up | `gcloud compute instances list`; the log's `Connect timed out` | `gcloud compute instances start ledger-db --zone us-central1-a`; 40 s to healthy (drill 1) |
| Payments fail with insufficient funds | the payer's seeded balance is used up | the rejection reason; the account balance (`GET /api/v1/accounts/{id}/balance`) | use the other seeded payer account; see platform review 0001 |
| A settlement stays LEDGER_POSTED | a crash between the ledger posting and the clearing outcome | the settlement's status | the maintenance job (`POST /internal/jobs/maintenance`) recovers it after `stale-after` |
| Events are not reaching audit-ai | Pub/Sub push paused or failing | `gcloud pubsub subscriptions describe audit-settlement-events`; the dead-letter subscription | restore push settings (drill 2); `POST /audits/reconcile` for dead letters |
| Duplicate audit findings | should not happen: one claim per `event_id` | the finding's `event_id` | file a defect with the correlation id |
| Backup missing | the daily timer failed | `gcloud storage ls gs://<project>-ledger-backups` | `sudo systemctl start ledger-backup.service` on the VM (over IAP) |
| Ledger not balanced | should not happen (invariant I-2) | `isBalanced` and the scenario's step 8 | SEV-1: stop callers, preserve the database, compare entries by settlement |
