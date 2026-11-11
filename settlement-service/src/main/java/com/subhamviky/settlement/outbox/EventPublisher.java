package com.subhamviky.settlement.outbox;

/** Port to the message broker. The Azure realisation is a Service Bus sender (infra/main.tf). */
public interface EventPublisher {
    void publish(String eventType, String payload);
}
