package com.subhamviky.settlement.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Policy-gate settings from application.yml (settlement.policy.*). */
public record PolicySettings(BigDecimal maxAmount, Set<String> blockedAccounts) {

    /** The settings SettlementPolicyAgent reads from its request context (e2a-java RequestContext). */
    public Map<String, Object> toSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("max_amount", maxAmount.toPlainString());
        settings.put("blocked_accounts", Set.copyOf(blockedAccounts));
        return settings;
    }
}
