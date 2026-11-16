package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusMessage;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The message an E2A consumer (audit-ai) receives: the contract JSON, keyed by the event id. */
class ServiceBusEventPublisherTest {

    @Test
    void theMessageCarriesTheEventAndWhatTheConsumerNeeds() {
        UUID eventId = UUID.randomUUID();
        String payload = "{\"event_id\":\"" + eventId + "\",\"event_type\":\"settlement.completed\",\"tenant_id\":\"acme\"}";
        OutboxEvent event = new OutboxEvent(eventId, "acme", UUID.randomUUID(), "settlement.completed", payload);

        ServiceBusMessage message = ServiceBusEventPublisher.toMessage(event);

        assertThat(message.getBody().toString()).isEqualTo(payload);
        assertThat(message.getMessageId()).isEqualTo(eventId.toString());
        assertThat(message.getContentType()).isEqualTo("application/json");
        assertThat(message.getApplicationProperties())
                .containsEntry("tenant_id", "acme")
                .containsEntry("idempotency_key", eventId.toString())
                .containsEntry("event_type", "settlement.completed");
    }
}
