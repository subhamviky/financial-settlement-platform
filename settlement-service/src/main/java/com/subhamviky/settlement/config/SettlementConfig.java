package com.subhamviky.settlement.config;

import com.subhamviky.settlement.service.SettlementPolicyAgent;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the G2C-generated policy agent (a plain class) and enables the outbox relay schedule. */
@Configuration
@EnableScheduling
public class SettlementConfig {

    @Bean
    public SettlementPolicyAgent settlementPolicyAgent() {
        return new SettlementPolicyAgent();
    }

    @Bean
    public PolicySettings policySettings(@Value("${settlement.policy.max-amount}") BigDecimal maxAmount,
                                         @Value("${settlement.policy.blocked-accounts}") Set<String> blockedAccounts) {
        return new PolicySettings(maxAmount, blockedAccounts);
    }
}
