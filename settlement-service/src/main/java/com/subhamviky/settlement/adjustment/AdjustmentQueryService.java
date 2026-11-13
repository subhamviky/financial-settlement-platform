package com.subhamviky.settlement.adjustment;

import com.subhamviky.e2a.cqrs.BaseQueryService;
import com.subhamviky.settlement.settlement.SettlementRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read path for GET /api/v1/settlements/{id}/adjustments. Tenant-scoped; 404 for an unknown settlement. */
@Service
public class AdjustmentQueryService extends BaseQueryService<String, List<AdjustmentView>> {

    private final SettlementRepository settlements;
    private final LedgerAdjustmentRepository adjustments;

    public AdjustmentQueryService(SettlementRepository settlements, LedgerAdjustmentRepository adjustments) {
        this.settlements = settlements;
        this.adjustments = adjustments;
    }

    @Override
    protected Optional<List<AdjustmentView>> executeDbRead(String id, String tenantId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
        return settlements.findByIdAndTenantId(uuid, tenantId).map(s ->
                adjustments.findByTenantIdAndSettlementIdOrderByCreatedAtAsc(tenantId, uuid).stream()
                        .map(a -> AdjustmentView.of(a, s.getCurrency())).toList());
    }
}
