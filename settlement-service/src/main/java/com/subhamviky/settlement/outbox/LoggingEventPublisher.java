package com.subhamviky.settlement.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Local and test publisher: writes each event to the log. Consumers read the event feed API instead. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String eventType, String payload) {
        logger.info("outbox published {} {}", eventType, payload);
    }
}
