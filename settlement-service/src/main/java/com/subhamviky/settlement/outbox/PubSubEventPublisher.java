package com.subhamviky.settlement.outbox;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Google Cloud (hub design note GCP-01): publishes each outbox event to a Pub/Sub topic, from which
 * push subscriptions deliver it to audit-ai-service and reconciliation-service. The data is the
 * event's JSON exactly as the outbox holds it (contracts/events/settlement-event.v1.schema.json);
 * the attributes carry what an E2A consumer reads: tenant_id, idempotency_key (the event id),
 * event_type and event_id. The publish is waited for: a failure throws and the relay retries the
 * event on its next run (at-least-once; consumers de-duplicate on the event id).
 */
public class PubSubEventPublisher implements EventPublisher {

    private final Publisher publisher;

    public PubSubEventPublisher(Publisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            publisher.publish(toMessage(event)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing event " + event.getEventId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("could not publish event " + event.getEventId() + " to Pub/Sub", e);
        }
    }

    /** The Pub/Sub message for one outbox event. */
    public static PubsubMessage toMessage(OutboxEvent event) {
        String id = event.getEventId().toString();
        return PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(event.getPayload(), StandardCharsets.UTF_8))
                .putAttributes("tenant_id", event.getTenantId())
                .putAttributes("idempotency_key", id)
                .putAttributes("event_type", event.getEventType())
                .putAttributes("event_id", id)
                .build();
    }
}
