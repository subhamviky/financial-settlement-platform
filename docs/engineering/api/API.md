# API reference

Sources: `settlement-service/.../api/*Controller.java` and `audit-ai-service/src/audit_ai_service/routers/`. On Cloud Run both services are private and IAM verifies the caller's Google ID token; the deployment grants tenants and actions.

## settlement-service

| Route | Headers | Responses |
|---|---|---|
| `POST /api/v1/settlements` | `X-Tenant-Id`, `Idempotency-Key`, optional `X-Correlation-Id` | 201 COMPLETED or COMPENSATED · 422 REJECTED (insufficient funds, wrong account, policy) · 409 conflict or in progress · 400 invalid |
| `GET /api/v1/settlements/{id}` | `X-Tenant-Id` | 200 · 404 |
| `GET /api/v1/accounts/{id}/balance` | `X-Tenant-Id` | 200 · 404 |
| `POST /api/v1/settlements/{id}/adjustments` | `X-Tenant-Id`, `Idempotency-Key` | 201 (write-off or refund) · 404 · 422 beyond the limit · 409 concurrent adjustment or key in progress |
| `GET /api/v1/settlements/{id}/adjustments` | `X-Tenant-Id` | 200 (used for the read-back) |
| `GET /api/v1/events?afterSequence=&limit=` | `X-Tenant-Id` | 200 the event feed (versioned JSON-Schema contract in `contracts/events`) |
| `POST /internal/jobs/maintenance` | scheduler identity | relay of the outbox and recovery of stale claims |
| `GET /actuator/health` | none | 200 |

A replay carries `Idempotent-Replayed: true`. Every response carries `X-Correlation-Id`.

## audit-ai-service

| Route | Responses |
|---|---|
| `GET /audits?anomaly=`, `GET /audits/{settlementId}` | 200 findings (verdict, risk band, rule hits, explanation, `degraded`) · 404 |
| `POST /audits/poll`, `/audits/consume`, `/audits/reconcile` | 200 (pull the feed; consume the queue; confirm or replay dead letters) |
| `POST /pubsub/settlement-events` | Pub/Sub push endpoint (204 on success; non-2xx makes Pub/Sub redeliver) |
| `GET /health` | 200, no identity |

Findings are idempotent per `event_id`. The audit service never changes the ledger.
