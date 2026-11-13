package com.subhamviky.settlement.service;

import com.subhamviky.e2a.agent.BaseAgent;
import com.subhamviky.e2a.core.GovernanceRefusal;
import com.subhamviky.e2a.core.RequestContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SettlementPolicyAgent — a deterministic E2A v1.2 agent (e2a-java): no model is called.
 *
 * run() (inherited, final) runs the v1.2 lifecycle. buildMessages() returns null (no model
 * step); applyPolicy() checks the settlement against the settings in the request context and
 * refuses with a typed POLICY_VIOLATION. The caller treats every decision other than APPROVED as
 * a rejection, and an error also REJECTS (handleError), so the gate fails closed.
 */
public class SettlementPolicyAgent extends BaseAgent {

    @Override
    protected List<Map<String, String>> buildMessages(Map<String, Object> state, RequestContext ctx) {
        return null;                                       // deterministic: no model step
    }

    @Override
    protected void applyPolicy(Map<String, Object> state, RequestContext ctx) {
        BigDecimal amount = new BigDecimal(String.valueOf(state.get("amount")));
        BigDecimal maxAmount = new BigDecimal(String.valueOf(ctx.setting("max_amount", "1000000.00")));
        Collection<?> blocked = ctx.setting("blocked_accounts", List.of());
        String payer = String.valueOf(state.get("payer_account"));
        String payee = String.valueOf(state.get("payee_account"));

        List<String> violations = new ArrayList<>();
        if (payer.equals(payee)) {
            violations.add("payer and payee must be different accounts");
        }
        if (amount.compareTo(maxAmount) > 0) {
            violations.add("amount " + amount.toPlainString() + " exceeds the policy limit " + maxAmount.toPlainString());
        }
        if (blocked.contains(payer) || blocked.contains(payee)) {
            violations.add("a blocked account is involved");
        }
        state.put("violations", violations);
        state.put("decision", violations.isEmpty() ? "APPROVED" : "REJECTED");
        if (!violations.isEmpty()) {
            throw new GovernanceRefusal("POLICY_VIOLATION", "settlement_policy", String.join("; ", violations), false);
        }
    }

    @Override
    protected void handleError(Exception error, Map<String, Object> state, RequestContext ctx) {
        super.handleError(error, state, ctx);
        state.put("decision", "REJECTED");
        state.put("violations", List.of("policy evaluation failed: " + error.getMessage()));
    }
}
