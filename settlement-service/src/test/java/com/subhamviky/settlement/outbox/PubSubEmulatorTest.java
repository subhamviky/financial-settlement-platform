package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** GCP-01, on the Pub/Sub emulator (PUBSUB_EMULATOR_HOST): what the relay publishes arrives intact. */
@EnabledIfEnvironmentVariable(named = "PUBSUB_EMULATOR_HOST", matches = ".+")
class PubSubEmulatorTest {

    @Test
    void anOutboxEventArrivesWithItsAttributes() throws Exception {
        String project = "demo-settlement";
        String id = UUID.randomUUID().toString().substring(0, 8);
        TopicName topic = TopicName.of(project, "settlement-events-" + id);
        SubscriptionName subscription = SubscriptionName.of(project, "audit-" + id);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(System.getenv("PUBSUB_EMULATOR_HOST")).usePlaintext().build();
        TransportChannelProvider channels = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
        Publisher publisher = null;
        try (TopicAdminClient topics = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                     .setTransportChannelProvider(channels).setCredentialsProvider(NoCredentialsProvider.create()).build());
             SubscriptionAdminClient subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                     .setTransportChannelProvider(channels).setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            topics.createTopic(topic);
            subscriptions.createSubscription(subscription, topic, PushConfig.getDefaultInstance(), 30);
            publisher = Publisher.newBuilder(topic).setChannelProvider(channels)
                    .setCredentialsProvider(NoCredentialsProvider.create()).build();

            UUID eventId = UUID.randomUUID();
            String payload = "{\"event_id\":\"" + eventId + "\",\"event_type\":\"settlement.completed\",\"tenant_id\":\"acme\"}";
            new PubSubEventPublisher(publisher).publish(
                    new OutboxEvent(eventId, "acme", UUID.randomUUID(), "settlement.completed", payload));

            PubsubMessage received = null;
            for (int i = 0; i < 10 && received == null; i++) {
                PullResponse pulled = subscriptions.pull(subscription, 10);
                for (ReceivedMessage m : pulled.getReceivedMessagesList()) {
                    subscriptions.acknowledge(subscription, java.util.List.of(m.getAckId()));
                    if (eventId.toString().equals(m.getMessage().getAttributesOrDefault("event_id", ""))) {
                        received = m.getMessage();
                    }
                }
            }
            assertThat(received).isNotNull();
            assertThat(received.getData().toString(StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(received.getAttributesMap()).containsEntry("tenant_id", "acme")
                    .containsEntry("idempotency_key", eventId.toString());
        } finally {
            if (publisher != null) {
                publisher.shutdown();
                publisher.awaitTermination(5, TimeUnit.SECONDS);
            }
            channel.shutdownNow();
        }
    }
}
