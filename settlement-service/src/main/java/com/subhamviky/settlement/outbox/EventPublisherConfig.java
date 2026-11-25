package com.subhamviky.settlement.outbox;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
 * With settlement.events.pubsub.topic set instead (SETTLEMENT_EVENTS_PUBSUB_TOPIC; Google Cloud,
 * hub design note GCP-01), events go to that Pub/Sub topic in settlement.events.pubsub.project
 * (GOOGLE_CLOUD_PROJECT), with the Cloud Run service account (Application Default Credentials):
 * no secret. Against the emulator, settlement.events.pubsub.emulator-host (PUBSUB_EMULATOR_HOST).
 * Without either, the logging publisher (local runs and tests).
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

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "settlement.events.pubsub", name = "topic")
    public Publisher settlementEventsPublisher(
            @Value("${settlement.events.pubsub.topic}") String topic,
            @Value("${settlement.events.pubsub.project:${GOOGLE_CLOUD_PROJECT:}}") String project,
            @Value("${settlement.events.pubsub.emulator-host:${PUBSUB_EMULATOR_HOST:}}") String emulatorHost)
            throws IOException {
        if (project.isBlank()) {
            throw new IllegalStateException("settlement.events.pubsub.topic is set: set its project (GOOGLE_CLOUD_PROJECT) too");
        }
        Publisher.Builder builder = Publisher.newBuilder(TopicName.of(project, topic));
        if (!emulatorHost.isBlank()) {
            emulatorChannel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build();
            builder.setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(emulatorChannel)))
                    .setCredentialsProvider(NoCredentialsProvider.create());
        }
        return builder.build();
    }

    /** The emulator's channel: the publisher does not own it, so it is closed here. */
    private ManagedChannel emulatorChannel;

    @Bean
    @ConditionalOnProperty(prefix = "settlement.events.pubsub", name = "topic")
    public EventPublisher pubSubEventPublisher(Publisher settlementEventsPublisher) {
        return new PubSubEventPublisher(settlementEventsPublisher);
    }

    /** Flushes what the publisher still holds when the application stops. */
    @Bean
    @ConditionalOnProperty(prefix = "settlement.events.pubsub", name = "topic")
    public AutoCloseable pubSubPublisherShutdown(Publisher settlementEventsPublisher) {
        return () -> {
            settlementEventsPublisher.shutdown();
            settlementEventsPublisher.awaitTermination(5, TimeUnit.SECONDS);
            if (emulatorChannel != null) {
                emulatorChannel.shutdownNow();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }
}
