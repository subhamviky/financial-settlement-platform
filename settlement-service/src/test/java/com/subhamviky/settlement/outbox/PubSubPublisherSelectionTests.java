package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** GCP-01: with a Pub/Sub topic configured, the outbox publishes there (no connection until a publish). */
@SpringBootTest(properties = {
        "settlement.events.pubsub.topic=settlement-events",
        "settlement.events.pubsub.project=demo-project",
        "settlement.events.pubsub.emulator-host=localhost:1",
        "settlement.outbox.relay-interval-ms=3600000",
        "settlement.recovery.interval-ms=3600000"})
@DirtiesContext       // its publisher is closed after this class, not at JVM exit
class PubSubPublisherSelectionTests {

    @Autowired
    EventPublisher publisher;

    @Test
    void withATopicThePubSubPublisherIsUsed() {
        assertThat(publisher).isInstanceOf(PubSubEventPublisher.class);
    }
}
