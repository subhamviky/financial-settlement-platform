package com.subhamviky.settlement.settlement;

import java.util.Map;
import java.util.Set;

/** Settlement state machine. Only the listed transitions are legal. */
public enum SettlementStatus {
    RECEIVED, LEDGER_POSTED, COMPLETED, COMPENSATING, COMPENSATED, REJECTED;

    private static final Map<SettlementStatus, Set<SettlementStatus>> NEXT = Map.of(
            RECEIVED, Set.of(LEDGER_POSTED, REJECTED),
            LEDGER_POSTED, Set.of(COMPLETED, COMPENSATING),
            COMPENSATING, Set.of(COMPENSATED));

    public boolean canMoveTo(SettlementStatus next) {
        return NEXT.getOrDefault(this, Set.of()).contains(next);
    }
}
