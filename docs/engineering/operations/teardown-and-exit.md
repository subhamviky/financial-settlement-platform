# Teardown: the ledger and the audit service

Platform order and verification: [teardown](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/teardown-and-exit.md). Resources to remove from this repository's stacks, after exporting what you want to keep:

1. Export: the last scenario summary, a final `pg_dump` if the data matters (it is synthetic), screenshots of the SLO page.
2. `terraform destroy` in `audit-ai-service/infra/gcp`, then `settlement-service/infra/gcp` (the VM, the backup bucket, the secrets, the topic and subscriptions, the scheduler job).
3. Delete the repository variables and the Workload Identity provider.

The Azure stacks (`*/infra`) were never applied live and need no teardown.
