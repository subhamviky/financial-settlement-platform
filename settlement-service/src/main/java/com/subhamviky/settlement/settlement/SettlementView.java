package com.subhamviky.settlement.settlement;

import java.math.BigDecimal;
import java.util.UUID;

/** Read model returned by the API. */
public record SettlementView(UUID settlementId, String payerAccount, String payeeAccount, BigDecimal amount,
                             String currency, SettlementStatus status, String reason) {

    public static SettlementView of(Settlement s) {
        return new SettlementView(s.getId(), s.getPayerAccount(), s.getPayeeAccount(), s.getAmount(),
                s.getCurrency(), s.getStatus(), s.getFailureReason());
    }
}
