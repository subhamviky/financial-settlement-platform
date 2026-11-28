# SLOs: settlement-service and audit-ai-service

The objectives, alerts and the error-budget policy are platform-wide and defined once in [the assistant repository](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/slo-and-error-budget.md), implemented by its `landscape/observability` stack. What applies here:

| Service | Objective | Note |
|---|---|---|
| settlement-service | 99% of requests not 5xx; 95% within 1 s (28 days) | no model in the path, so it has a latency objective |
| audit-ai-service | 99% of requests not 5xx | a model is in the path: no latency objective |

Evidence: on 27 November 2026 the ledger-outage drill left settlement-service with 40.7% of the availability budget and 60.5% of the latency budget (the 30 s connection waits counted as errors and as slow). The 5 s connection timeout (ADR 0012) changes that behaviour; repeat drill 1 to measure it.
