package com.subhamviky.settlement.adjustment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One adjustment of a settlement. Never updated or deleted. */
@Entity
@Table(name = "ledger_adjustment")
public class LedgerAdjustment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false)
    private AdjustmentType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "debit_account", nullable = false)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false)
    private String creditAccount;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAdjustment() {
    }

    LedgerAdjustment(String tenantId, PostAdjustmentCommand command, String debitAccount, String creditAccount) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.settlementId = command.settlementId();
        this.type = command.type();
        this.amount = command.amount();
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
        this.reason = command.reason();
        this.reference = command.reference();
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getSettlementId() { return settlementId; }
    public AdjustmentType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getDebitAccount() { return debitAccount; }
    public String getCreditAccount() { return creditAccount; }
    public String getReason() { return reason; }
    public String getReference() { return reference; }
}
