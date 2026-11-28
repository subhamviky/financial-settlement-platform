# Requirements and user stories

Traced requirements are in the design document (section 2); here are the users' stories with acceptance criteria that are tests.

| # | As a... | I want... | So that... | Acceptance |
|---|---|---|---|---|
| S1 | payment initiator | to submit a settlement with an idempotency key | a retry never moves money twice | same key replays the stored result; a different body with the same key is 409 |
| S2 | finance controller | the ledger always balanced | I can trust the books | every settlement writes equal debits and credits; the scenario's step 8 passes |
| S3 | finance controller | declined payments reversed | no money is stranded | a clearing decline yields COMPENSATED with reversal entries and restored balances |
| S4 | reconciliation analyst | write-offs and refunds posted within limits | a bad adjustment cannot exceed the limit | over-limit gives 422; two racing adjustments: one commits, the other 409 |
| S5 | operator | a crashed settlement finished or compensated | nothing stays half-done | recovery completes or compensates it with one event |
| S6 | downstream system | every state change announced exactly in step with the ledger | I never act on a lie | outbox in the same transaction; contract tested against real events |
| S7 | auditor | risky settlements explained with grounded text | I can see why | the rules decide; the explanation cites policy; degraded when the model fails |
| S8 | operator | recovery from a stopped database, a paused feed, a lost backup | outages are routine | 40 s, 10 s, 31 s as recorded in the drills |

Non-functional: private by default with verified identity; a request fails fast when the database is down (5 s). Out of scope: real money, multi-currency conversion, a user interface.
