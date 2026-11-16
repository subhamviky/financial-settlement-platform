package com.subhamviky.settlement.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local and test publisher: writes each event to the log. Consumers read the event feed API instead. */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        logger.info("outbox published {} {}", event.getEventType(), event.getPayload());
    }
}
