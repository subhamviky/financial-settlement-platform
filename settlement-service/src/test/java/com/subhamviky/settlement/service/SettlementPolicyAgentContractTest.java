package com.subhamviky.settlement.service;

import com.subhamviky.e2a.E2AConfig;
import com.subhamviky.e2a.agent.BaseAgent;
import com.subhamviky.e2a.core.RequestContext;
import com.subhamviky.e2a.test.AgentContract;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The E2A agent guarantees (e2a-test AgentContract), plus the policy decisions themselves. */
class SettlementPolicyAgentContractTest extends AgentContract {

    @Override
    protected BaseAgent agent() {
        return new SettlementPolicyAgent();
    }

    @Override
    protected Map<String, Object> validState() {
        return Map.of("payer_account", "ACC-1000", "payee_account", "ACC-2000", "amount", "125.00", "currency", "EUR");
    }

    private Map<String, Object> decide(Map<String, Object> changes, Map<String, Object> settings) {
        Map<String, Object> state = new HashMap<>(validState());
        state.putAll(changes);
        return agent().run(state, RequestContext.begin("acme").withSettings(settings), E2AConfig.empty());
    }

    @Test
    void aValidSettlementIsApproved() {
        Map<String, Object> s = decide(Map.of(), Map.of("max_amount", "1000.00"));
        assertThat(s.get("decision")).isEqualTo("APPROVED");
        assertThat(s).doesNotContainKey("refusal");
    }

    @Test
    void violationsAreATypedRefusalAndRejected() {
        Map<String, Object> s = decide(Map.of("amount", "5000.00", "payee_account", "ACC-1000"),
                Map.of("max_amount", "1000.00", "blocked_accounts", Set.of()));
        assertThat(s.get("decision")).isEqualTo("REJECTED");
        assertThat((List<?>) s.get("violations")).hasSize(2);
        assertThat(((Map<?, ?>) s.get("refusal")).get("code")).isEqualTo("POLICY_VIOLATION");
    }

    @Test
    void aBadInputFailsClosed() {
        Map<String, Object> s = decide(Map.of("amount", "not-a-number"), Map.of());
        assertThat(s.get("decision")).isEqualTo("REJECTED");
        assertThat(s.get("violations").toString()).contains("policy evaluation failed");
    }
}
