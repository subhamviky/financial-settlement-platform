package com.subhamviky.settlement.settlement;

/**
 * Port to the external clearing step. True = cleared, false = declined.
 * Contract: asking again about the same settlement id gives the same answer (the clearing
 * house de-duplicates on it), so StuckSettlementRecovery may ask after a crash.
 */
public interface ClearingGateway {
    boolean confirm(Settlement settlement);
}
