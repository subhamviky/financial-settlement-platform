package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** On the Service Bus emulator (SERVICEBUS_CONNECTION_STRING): what the relay sends arrives intact. */
@EnabledIfEnvironmentVariable(named = "SERVICEBUS_CONNECTION_STRING", matches = ".+")
class ServiceBusEmulatorTest {

    @Test
    void anOutboxEventArrivesWithItsIdAndAttributes() {
        String connection = System.getenv("SERVICEBUS_CONNECTION_STRING");
        String queue = System.getenv().getOrDefault("SETTLEMENT_TEST_QUEUE", "settlement-test-events");
        UUID eventId = UUID.randomUUID();
        String payload = "{\"event_id\":\"" + eventId + "\",\"event_type\":\"settlement.completed\",\"tenant_id\":\"acme\"}";

        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder().connectionString(connection)
                .sender().queueName(queue).buildClient();
             ServiceBusReceiverClient receiver = new ServiceBusClientBuilder().connectionString(connection)
                .receiver().queueName(queue).buildClient()) {
            new ServiceBusEventPublisher(sender).publish(
                    new OutboxEvent(eventId, "acme", UUID.randomUUID(), "settlement.completed", payload));

            ServiceBusReceivedMessage received = null;
            for (int i = 0; i < 10 && received == null; i++) {
                for (ServiceBusReceivedMessage m : receiver.receiveMessages(10, Duration.ofSeconds(2))) {
                    receiver.complete(m);
                    if (eventId.toString().equals(m.getMessageId())) {
                        received = m;
                    }
                }
            }
            assertThat(received).isNotNull();
            assertThat(received.getBody().toString()).isEqualTo(payload);
            assertThat(received.getApplicationProperties())
                    .containsEntry("tenant_id", "acme")
                    .containsEntry("idempotency_key", eventId.toString())
                    .containsEntry("event_type", "settlement.completed");
        }
    }
}
