package com.subhamviky.settlement.adjustment;

import java.math.BigDecimal;
import java.util.UUID;

/** Read model of an adjustment. */
public record AdjustmentView(UUID adjustmentId, UUID settlementId, AdjustmentType type, BigDecimal amount,
                             String currency, String debitAccount, String creditAccount, String reason,
                             String reference) {

    public static AdjustmentView of(LedgerAdjustment a, String currency) {
        return new AdjustmentView(a.getId(), a.getSettlementId(), a.getType(), a.getAmount(), currency,
                a.getDebitAccount(), a.getCreditAccount(), a.getReason(), a.getReference());
    }
}
