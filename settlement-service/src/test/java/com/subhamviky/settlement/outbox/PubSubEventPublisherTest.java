package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.pubsub.v1.PubsubMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GCP-01: the Pub/Sub message carries the outbox JSON unchanged and what an E2A consumer reads. */
class PubSubEventPublisherTest {

    @Test
    void theMessageIsTheOutboxJsonWithTheConsumersAttributes() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"event_id\":\"" + eventId + "\",\"event_type\":\"settlement.completed\",\"tenant_id\":\"acme\"}";
        PubsubMessage m = PubSubEventPublisher.toMessage(
                new OutboxEvent(eventId, "acme", UUID.randomUUID(), "settlement.completed", payload));
        assertThat(m.getData().toString(StandardCharsets.UTF_8)).isEqualTo(payload);
        assertThat(m.getAttributesMap()).containsEntry("tenant_id", "acme")
                .containsEntry("idempotency_key", eventId.toString())
                .containsEntry("event_id", eventId.toString())
                .containsEntry("event_type", "settlement.completed");
    }
}
