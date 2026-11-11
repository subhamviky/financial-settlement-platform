package com.subhamviky.settlement.settlement;

import com.subhamviky.settlement.cqrs.BaseQueryService;
import com.subhamviky.settlement.ledger.AccountRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read path for GET /api/v1/accounts/{id}/balance. Tenant-scoped. */
@Service
public class AccountQueryService extends BaseQueryService<String, AccountQueryService.BalanceView> {

    public record BalanceView(String accountId, BigDecimal balance, String currency) {
    }

    private final AccountRepository accounts;

    public AccountQueryService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    protected Optional<BalanceView> executeDbRead(String id, String tenantId) {
        return accounts.findByIdAndTenantId(id, tenantId)
                .map(a -> new BalanceView(a.getId(), a.getBalance(), a.getCurrency()));
    }
}
