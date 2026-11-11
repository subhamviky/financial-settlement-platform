package com.subhamviky.settlement.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One side of a double-entry posting. Entries are never updated or deleted; reversals are new entries. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    public enum Direction { DEBIT, CREDIT }

    public enum PostingType { POSTING, REVERSAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false)
    private PostingType postingType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    LedgerEntry(UUID settlementId, String accountId, Direction direction, BigDecimal amount, PostingType postingType) {
        this.settlementId = settlementId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.postingType = postingType;
        this.createdAt = Instant.now();
    }

    public UUID getSettlementId() { return settlementId; }
    public String getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public PostingType getPostingType() { return postingType; }
}
