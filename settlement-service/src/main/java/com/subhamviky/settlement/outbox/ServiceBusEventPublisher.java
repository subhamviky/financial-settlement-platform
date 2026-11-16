package com.subhamviky.settlement.outbox;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;

/**
 * Sends each outbox event to a Service Bus queue (audit-ai's settlement-events queue). The body is
 * the event's JSON exactly as the outbox holds it (the contract in
 * contracts/events/settlement-event.v1.schema.json); the message id is the event id, and the
 * application properties carry what an E2A consumer reads: tenant_id, idempotency_key (the event
 * id) and event_type. The send is synchronous: a failure throws and the relay retries.
 */
public class ServiceBusEventPublisher implements EventPublisher {

    private final ServiceBusSenderClient sender;

    public ServiceBusEventPublisher(ServiceBusSenderClient sender) {
        this.sender = sender;
    }

    @Override
    public void publish(OutboxEvent event) {
        sender.sendMessage(toMessage(event));
    }

    /** The Service Bus message for one outbox event. */
    public static ServiceBusMessage toMessage(OutboxEvent event) {
        ServiceBusMessage message = new ServiceBusMessage(event.getPayload());
        message.setMessageId(event.getEventId().toString());
        message.setContentType("application/json");
        message.getApplicationProperties().put("tenant_id", event.getTenantId());
        message.getApplicationProperties().put("idempotency_key", event.getEventId().toString());
        message.getApplicationProperties().put("event_type", event.getEventType());
        return message;
    }
}
