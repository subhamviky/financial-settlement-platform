-- StuckSettlementRecovery: settlements by status, oldest update first; and the request's
-- Idempotency-Key, so the client's idempotency record can be completed after a crash.
alter table settlement add column idempotency_key varchar(100);
create index settlement_status_updated on settlement (status, updated_at);
