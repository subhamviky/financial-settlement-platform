package com.subhamviky.settlement.outbox;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the outbox's publisher. With settlement.events.servicebus.queue set (environment
 * SETTLEMENT_EVENTS_SERVICEBUS_QUEUE), events go to that Service Bus queue:
 * <ul>
 *   <li>in Azure, to SETTLEMENT_EVENTS_SERVICEBUS_NAMESPACE (&lt;name&gt;.servicebus.windows.net) with
 *       the managed identity (Entra ID, DefaultAzureCredential): no secret;</li>
 *   <li>against the emulator, with SETTLEMENT_EVENTS_SERVICEBUS_CONNECTION_STRING.</li>
 * </ul>
 * Without it, the logging publisher (local runs and tests).
 */
@Configuration
public class EventPublisherConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "settlement.events.servicebus", name = "queue")
    public ServiceBusSenderClient settlementEventsSender(
            @Value("${settlement.events.servicebus.queue}") String queue,
            @Value("${settlement.events.servicebus.namespace:}") String namespace,
            @Value("${settlement.events.servicebus.connection-string:}") String connectionString) {
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder();
        if (!connectionString.isBlank()) {
            builder.connectionString(connectionString);
        } else if (!namespace.isBlank()) {
            builder.fullyQualifiedNamespace(namespace).credential(new DefaultAzureCredentialBuilder().build());
        } else {
            throw new IllegalStateException("settlement.events.servicebus.queue is set: set its namespace "
                    + "(managed identity) or connection-string (emulator) too");
        }
        return builder.sender().queueName(queue).buildClient();
    }

    @Bean
    @ConditionalOnProperty(prefix = "settlement.events.servicebus", name = "queue")
    public EventPublisher serviceBusEventPublisher(ServiceBusSenderClient settlementEventsSender) {
        return new ServiceBusEventPublisher(settlementEventsSender);
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }
}
