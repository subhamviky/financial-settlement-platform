package com.subhamviky.settlement.settlement;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByIdAndTenantId(UUID id, String tenantId);
}
