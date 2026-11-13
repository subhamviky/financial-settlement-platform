package com.subhamviky.settlement.settlement;

import com.subhamviky.e2a.E2AConfig;
import com.subhamviky.e2a.core.RequestContext;
import com.subhamviky.settlement.config.PolicySettings;
import com.subhamviky.e2a.cqrs.BaseCommandService;
import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.e2a.cqrs.CqrsResult;
import com.subhamviky.settlement.service.SettlementPolicyAgent;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Write path for POST /api/v1/settlements: the policy gate (an e2a-java agent, deterministic,
 * fails closed) runs before any money moves; then the saga.
 */
@Service
public class SubmitSettlementCommandService extends BaseCommandService<SubmitSettlementCommand> {

    private final SettlementPolicyAgent policyAgent;
    private final PolicySettings policySettings;
    private final SettlementSaga saga;

    public SubmitSettlementCommandService(Validator validator, SettlementPolicyAgent policyAgent,
                                          PolicySettings policySettings, SettlementSaga saga) {
        super(validator, 2_500);
        this.policyAgent = policyAgent;
        this.policySettings = policySettings;
        this.saga = saga;
    }

    @Override
    protected CqrsResult executeBusinessLogic(SubmitSettlementCommand command, CommandContext ctx) {
        Map<String, Object> state = new HashMap<>();
        state.put("tenant_id", ctx.tenantId());
        state.put("payer_account", command.payerAccount());
        state.put("payee_account", command.payeeAccount());
        state.put("amount", command.amount().toPlainString());
        state.put("currency", command.currency());
        RequestContext request = new RequestContext(ctx.tenantId(), ctx.correlationId(), ctx.idempotencyKey(), null,
                policySettings.toSettings());
        policyAgent.run(state, request, E2AConfig.empty());
        if (!"APPROVED".equals(state.get("decision"))) {            // anything else fails closed
            Object violations = state.getOrDefault("violations", List.of("policy decision missing"));
            Settlement rejected = saga.reject(command, ctx, "policy: " + violations);
            return CqrsResult.of(422, SettlementView.of(rejected));
        }
        Settlement s = saga.run(command, ctx);
        int status = s.getStatus() == SettlementStatus.REJECTED ? 422 : 201;
        return CqrsResult.of(status, SettlementView.of(s));
    }
}
