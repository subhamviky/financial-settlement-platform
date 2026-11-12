package com.subhamviky.settlement.adjustment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAdjustmentRepository extends JpaRepository<LedgerAdjustment, UUID> {
    List<LedgerAdjustment> findByTenantIdAndSettlementIdOrderByCreatedAtAsc(String tenantId, UUID settlementId);
}
