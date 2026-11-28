# Solution overview

*One page. Synthetic data, one author.*

**The problem.** Money moves between accounts; retries, crashes and concurrent requests must never double-post, unbalance the books or leave a payment half done, and every outcome must reach other systems reliably.

**What it is.** The system of record of an order-to-cash landscape: a Java settlement service (idempotent submission, a policy gate, an append-only balanced ledger, compensation, write-offs and refunds within limits, a transactional outbox and an event feed) and a Python audit service in which deterministic rules decide, a model explains, and the system of record confirms. It runs on Azure (reference) and on Google Cloud.

**Evidence.** All 14 steps of the landscape scenario pass against the deployed services; the ledger recovered in 40 s from a stopped VM; a backup restored 54 of 54 settlements in 31 s; paused event delivery drained without loss; a crash between ledger posting and clearing is recovered to one outcome. Design: `docs/design/SETTLEMENT_PLATFORM_DESIGN.md`; decisions: [ADRs](../adr/README.md).

**Limits.** One VM and disk for the ledger; a per-replica velocity window; the Azure deployment never ran live; no load test.
