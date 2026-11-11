package com.subhamviky.settlement.settlement;

/** Port to the external clearing step. True = cleared, false = declined. */
public interface ClearingGateway {
    boolean confirm(Settlement settlement);
}
