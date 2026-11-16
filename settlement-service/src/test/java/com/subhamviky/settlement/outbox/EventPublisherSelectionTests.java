package com.subhamviky.settlement.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Without a Service Bus queue configured, the outbox publishes to the log (local runs and tests). */
@SpringBootTest
class EventPublisherSelectionTests {

    @Autowired
    EventPublisher publisher;

    @Test
    void withoutAQueueTheLoggingPublisherIsUsed() {
        assertThat(publisher).isInstanceOf(LoggingEventPublisher.class);
    }
}
