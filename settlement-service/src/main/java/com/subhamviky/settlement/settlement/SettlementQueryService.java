package com.subhamviky.settlement.settlement;

import com.subhamviky.settlement.cqrs.BaseQueryService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read path for GET /api/v1/settlements/{id}. Tenant-scoped. */
@Service
public class SettlementQueryService extends BaseQueryService<String, SettlementView> {

    private final SettlementRepository settlements;

    public SettlementQueryService(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    @Override
    protected Optional<SettlementView> executeDbRead(String id, String tenantId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
        return settlements.findByIdAndTenantId(uuid, tenantId).map(SettlementView::of);
    }
}
