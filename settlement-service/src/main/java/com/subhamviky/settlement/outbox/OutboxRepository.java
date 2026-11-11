package com.subhamviky.settlement.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Pageable page);

    List<OutboxEvent> findByTenantIdAndIdGreaterThanOrderByIdAsc(String tenantId, Long afterId, Pageable page);

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(UUID aggregateId);
}
