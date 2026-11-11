package com.subhamviky.settlement.ledger;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {
    Optional<Account> findByIdAndTenantId(String id, String tenantId);
}
