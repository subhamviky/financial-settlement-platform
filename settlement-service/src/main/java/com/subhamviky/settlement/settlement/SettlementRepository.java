package com.subhamviky.settlement.settlement;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByIdAndTenantId(UUID id, String tenantId);

    /** Same row, with its version forced up at commit: concurrent writers of one settlement conflict. */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    Optional<Settlement> findForUpdateByIdAndTenantId(UUID id, String tenantId);
}
