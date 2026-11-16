package com.subhamviky.settlement.outbox;

/**
 * Port to the message broker. Two realisations, chosen by configuration (EventPublisherConfig):
 * ServiceBusEventPublisher sends to audit-ai's Service Bus queue; LoggingEventPublisher (local
 * and tests) writes to the log, and consumers read the event feed API instead.
 */
public interface EventPublisher {

    /** Publish one outbox event. Throws when it cannot: the relay's transaction rolls back and
     *  the event is published again on the next run (at-least-once). */
    void publish(OutboxEvent event);
}
