-- Ledger adjustments on a completed settlement: write-offs of short payments and refunds
-- of duplicate payments, requested by the reconciliation engine. Append-only.

create table ledger_adjustment (
    id              uuid           primary key,
    tenant_id       varchar(40)    not null,
    settlement_id   uuid           not null,
    adjustment_type varchar(12)    not null,
    amount          numeric(19, 2) not null,
    debit_account   varchar(40)    not null,
    credit_account  varchar(40)    not null,
    reason          varchar(500)   not null,
    reference       varchar(100)   not null,
    created_at      timestamp with time zone not null
);
create index ledger_adjustment_settlement on ledger_adjustment (tenant_id, settlement_id);

-- Expense account that absorbs written-off differences for tenant "acme".
insert into account (id, tenant_id, currency, balance) values
    ('ACC-WRITEOFF', 'acme', 'EUR', 0.00);
