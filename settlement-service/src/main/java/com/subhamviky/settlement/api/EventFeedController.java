package com.subhamviky.settlement.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.settlement.outbox.OutboxEvent;
import com.subhamviky.settlement.outbox.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pull feed of the outbox for consumers (the audit AI service): events after a
 * sequence number, in order. Consumers keep their own position and de-duplicate
 * on event_id.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventFeedController {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public EventFeedController(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @GetMapping
    public Map<String, Object> feed(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestParam(defaultValue = "0") long afterSequence,
                                    @RequestParam(defaultValue = "100") int limit) throws JsonProcessingException {
        List<Map<String, Object>> items = new ArrayList<>();
        long last = afterSequence;
        for (OutboxEvent e : outbox.findByTenantIdAndIdGreaterThanOrderByIdAsc(tenantId, afterSequence,
                PageRequest.of(0, Math.max(1, Math.min(limit, 500))))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", e.getId());
            item.put("event", json.readTree(e.getPayload()));
            items.add(item);
            last = e.getId();
        }
        return Map.of("events", items, "next_after_sequence", last);
    }
}
