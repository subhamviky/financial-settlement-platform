package com.subhamviky.settlement.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The settlement aggregate. Status changes only through {@link #moveTo}. */
@Entity
@Table(name = "settlement")
public class Settlement {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "payer_account", nullable = false)
    private String payerAccount;

    @Column(name = "payee_account", nullable = false)
    private String payeeAccount;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SettlementStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Settlement() {
    }

    public static Settlement receive(String tenantId, SubmitSettlementCommand command) {
        Settlement s = new Settlement();
        s.id = UUID.randomUUID();
        s.tenantId = tenantId;
        s.payerAccount = command.payerAccount();
        s.payeeAccount = command.payeeAccount();
        s.amount = command.amount();
        s.currency = command.currency();
        s.status = SettlementStatus.RECEIVED;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public void moveTo(SettlementStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("illegal transition " + status + " -> " + next);
        }
        status = next;
        updatedAt = Instant.now();
    }

    public void moveTo(SettlementStatus next, String reason) {
        moveTo(next);
        failureReason = reason;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPayerAccount() { return payerAccount; }
    public String getPayeeAccount() { return payeeAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public SettlementStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
}
