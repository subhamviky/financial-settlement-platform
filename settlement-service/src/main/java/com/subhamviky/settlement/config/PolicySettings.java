package com.subhamviky.settlement.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Policy-gate settings from application.yml (settlement.policy.*). */
public record PolicySettings(BigDecimal maxAmount, Set<String> blockedAccounts) {

    /** The config map handed to SettlementPolicyAgent.run(); a fresh mutable copy per call. */
    public Map<String, Object> toAgentConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_amount", maxAmount.toPlainString());
        config.put("blocked_accounts", blockedAccounts);
        config.put("max_latency", 2.0);
        return config;
    }
}
