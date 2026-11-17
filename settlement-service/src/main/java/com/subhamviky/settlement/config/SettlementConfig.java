package com.subhamviky.settlement.config;

import com.subhamviky.settlement.service.SettlementPolicyAgent;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import com.subhamviky.e2a.identity.IdentityAtIngress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the G2C-generated policy agent (a plain class), identity at ingress, and enables the schedules. */
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

    /** Identity at ingress (hub design note SI-01), from the E2A_IDENTITY_* environment; off when unset. */
    @Bean
    public IdentityAtIngress identityAtIngress() {
        return IdentityAtIngress.fromEnvironment();
    }
}
