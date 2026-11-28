# Engineering records: financial-settlement-platform

The design document says what the platform is; these say why it is that way, how it fails, how it is run and what was learned. Covers `settlement-service` (Java, the ledger) and `audit-ai-service` (Python, the AI audit).

| Area | Document |
|---|---|
| Decisions | [ADRs](adr/README.md) (14) |
| Learning from failure | [Post-incident reviews](postmortems/README.md) |
| Security | [Threat model](security/threat-model.md), [data classification](security/data-classification.md) |
| Reliability | [SLOs](operations/slo-and-error-budget.md), [incident response](operations/incident-response.md), [disaster recovery](operations/disaster-recovery.md) |
| Delivery | [Release process](operations/release-process.md), [`CHANGELOG.md`](../../CHANGELOG.md) |
| Cost and lifecycle | [Cost and capacity](operations/cost-and-capacity.md), [teardown](operations/teardown-and-exit.md) |
| Quality | [Test strategy](testing/test-strategy.md) |
| Interfaces | [API reference](api/API.md) |
| Product | [Requirements and user stories](product/requirements-and-user-stories.md), [solution overview](product/solution-overview.md), [onboarding](product/onboarding.md) |

Platform-wide documents (SLO policy, incident response, release process via `promote`, disaster recovery, cost, teardown) are maintained once in the [assistant repository](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/README.md); the pages here cover what is specific to the ledger and the audit service and link back.

> **Status:** all documents here are drafts written on 28 November 2026 from the repository history, design documents, drills and tests, awaiting the owner's review. Where a date or a detail was not recorded, the document says so.
