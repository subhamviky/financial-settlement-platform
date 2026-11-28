# Post-incident reviews

Blameless reviews of failures found while building and operating this service. None affected a user (the landscape is synthetic); each cost real time or showed a design gap. Severity scale and conventions: see the [platform README](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/postmortems/README.md). Template: [`TEMPLATE.md`](TEMPLATE.md).

## Reviews specific to this repository

| # | Review | Severity |
|---|---|---|
| [0001](0001-requests-hung-for-30-seconds-while-ledger.md) | Requests hung for 30 seconds while the ledger database was down | SEV-3 |

## Platform reviews that touched this repository

- [A seeded ledger account drained by the test payment](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/postmortems/0001-ledger-account-drained-by-test-payment.md)
- [First backup restore failed](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/postmortems/0007-first-backup-restore-failed.md)
- [Cloud Shell network failures](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/postmortems/0009-cloud-shell-network-failures.md)
