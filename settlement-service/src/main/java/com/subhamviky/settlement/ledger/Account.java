package com.subhamviky.settlement.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/** Ledger account. {@code @Version} gives optimistic concurrency on every balance change. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private BigDecimal balance;

    @Version
    private long version;

    protected Account() {
    }

    void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new LedgerRejection("insufficient funds in " + id);
        }
        balance = balance.subtract(amount);
    }

    /** Compensation only: a reversal must never fail on a funds check. */
    void debitForReversal(BigDecimal amount) {
        balance = balance.subtract(amount);
    }

    void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
}
