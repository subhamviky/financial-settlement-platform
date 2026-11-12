package com.subhamviky.settlement.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.settlement.adjustment.AdjustmentQueryService;
import com.subhamviky.settlement.adjustment.PostAdjustmentCommandService;
import com.subhamviky.settlement.cqrs.BaseCommandService;
import com.subhamviky.settlement.cqrs.BaseOrchestrator;
import com.subhamviky.settlement.cqrs.BaseQueryService;
import com.subhamviky.settlement.cqrs.CqrsRequest;
import com.subhamviky.settlement.cqrs.JpaIdempotencyStore;
import com.subhamviky.settlement.generated.Observability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Routes the settlement API's actions to the command and query services. */
@Component
public class SettlementOrchestrator extends BaseOrchestrator {

    public static final String SUBMIT = "settlement.submit";
    public static final String GET_SETTLEMENT = "settlement.get";
    public static final String GET_BALANCE = "account.balance";
    public static final String ADJUST = "settlement.adjust";
    public static final String GET_ADJUSTMENTS = "settlement.adjustments";

    private static final Logger logger = LoggerFactory.getLogger(SettlementOrchestrator.class);

    private final SubmitSettlementCommandService submit;
    private final SettlementQueryService settlementQuery;
    private final AccountQueryService accountQuery;
    private final PostAdjustmentCommandService adjust;
    private final AdjustmentQueryService adjustmentQuery;

    public SettlementOrchestrator(JpaIdempotencyStore store, ObjectMapper json, SubmitSettlementCommandService submit,
                                  SettlementQueryService settlementQuery, AccountQueryService accountQuery,
                                  PostAdjustmentCommandService adjust, AdjustmentQueryService adjustmentQuery) {
        super(store, json);
        this.submit = submit;
        this.settlementQuery = settlementQuery;
        this.accountQuery = accountQuery;
        this.adjust = adjust;
        this.adjustmentQuery = adjustmentQuery;
    }

    @Override
    protected boolean isReadScenario(CqrsRequest request) {
        return !SUBMIT.equals(request.action()) && !ADJUST.equals(request.action());
    }

    @Override
    protected BaseQueryService<?, ?> getQueryService(String action) {
        return switch (action) {
            case GET_BALANCE -> accountQuery;
            case GET_ADJUSTMENTS -> adjustmentQuery;
            default -> settlementQuery;
        };
    }

    @Override
    protected BaseCommandService<?> getCommandService(String action) {
        return ADJUST.equals(action) ? adjust : submit;
    }

    @Override
    protected void handleError(RuntimeException error, CqrsRequest request) {
        Observability.increment(request.action() + ".error");
        logger.error("{} failed for tenant {}: {}", request.action(), request.context().tenantId(), error.toString());
    }
}
