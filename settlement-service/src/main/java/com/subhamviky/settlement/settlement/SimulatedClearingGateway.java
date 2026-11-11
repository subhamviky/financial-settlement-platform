package com.subhamviky.settlement.settlement;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deterministic stand-in for a clearing house: declines amounts above the clearing limit. */
@Component
public class SimulatedClearingGateway implements ClearingGateway {

    private final BigDecimal maxAmount;

    public SimulatedClearingGateway(@Value("${settlement.clearing.max-amount}") BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    @Override
    public boolean confirm(Settlement settlement) {
        return settlement.getAmount().compareTo(maxAmount) <= 0;
    }
}
