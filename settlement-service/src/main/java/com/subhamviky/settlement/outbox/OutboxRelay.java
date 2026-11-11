package com.subhamviky.settlement.outbox;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox sweeper: publishes unpublished events in order and marks them
 * published. At-least-once: a crash between publish and commit re-publishes,
 * so consumers de-duplicate on event_id.
 */
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final EventPublisher publisher;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${settlement.outbox.relay-interval-ms:2000}")
    @Transactional
    public int relay() {
        List<OutboxEvent> batch = outbox.findByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, 100));
        for (OutboxEvent event : batch) {
            publisher.publish(event.getEventType(), event.getPayload());
            event.markPublished();
        }
        return batch.size();
    }
}
