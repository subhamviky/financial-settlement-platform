package com.subhamviky.settlement.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Logger logger = LoggerFactory.getLogger(SettlementOrchestrator.class);

    private final SubmitSettlementCommandService submit;
    private final SettlementQueryService settlementQuery;
    private final AccountQueryService accountQuery;

    public SettlementOrchestrator(JpaIdempotencyStore store, ObjectMapper json, SubmitSettlementCommandService submit,
                                  SettlementQueryService settlementQuery, AccountQueryService accountQuery) {
        super(store, json);
        this.submit = submit;
        this.settlementQuery = settlementQuery;
        this.accountQuery = accountQuery;
    }

    @Override
    protected boolean isReadScenario(CqrsRequest request) {
        return !SUBMIT.equals(request.action());
    }

    @Override
    protected BaseQueryService<?, ?> getQueryService(String action) {
        return GET_BALANCE.equals(action) ? accountQuery : settlementQuery;
    }

    @Override
    protected BaseCommandService<?> getCommandService(String action) {
        return submit;
    }

    @Override
    protected void handleError(RuntimeException error, CqrsRequest request) {
        Observability.increment(request.action() + ".error");
        logger.error("{} failed for tenant {}: {}", request.action(), request.context().tenantId(), error.toString());
    }
}
