# Disaster recovery: the ledger and the audit service

Platform plan: [disaster recovery](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/disaster-recovery.md). Specific here:

| Failure | Recovery | Measured |
|---|---|---|
| Ledger VM stopped | `gcloud compute instances start`; systemd restarts the database; the password is re-read from Secret Manager | 40 s (drill 1) |
| Ledger data lost | restore the latest daily `pg_dump` in Cloud Shell: download, `gunzip -t`, restore into a scratch database, compare counts, then restore deliberately | 54 of 54 settlements in 31 s (drill 3) |
| Audit feed paused | restore the push endpoint, service account and audience | 10 s to first delivery, backlog in the next minute (drill 2) |
| Poison events | `POST /audits/reconcile` confirms against the store or replays | tested |

RPO: up to 24 hours for the ledger on a disk loss (daily dump); zero for events that were committed (they are in the outbox). The VM has no standby (G-11). The backup bucket is in the same project as the data.
