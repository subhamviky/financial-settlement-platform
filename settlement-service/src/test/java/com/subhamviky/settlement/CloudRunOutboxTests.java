package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.settlement.outbox.OutboxEvent;
import com.subhamviky.settlement.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GCP-01: on Cloud Run the timers do not run between requests, so the outbox is relayed at the end
 * of each changing request, and Cloud Scheduler's maintenance call publishes whatever is left.
 */
class CloudRunOutboxTests {

    static UUID submit(MockMvc mvc, ObjectMapper json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/settlements").header("X-Tenant-Id", "acme")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAccount\":\"ACC-1000\",\"payeeAccount\":\"ACC-2000\",\"amount\":10.00,\"currency\":\"EUR\"}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("settlementId").asText());
    }

    @Nested
    @SpringBootTest(properties = {"settlement.outbox.relay-after-request=true",
            "settlement.outbox.relay-interval-ms=3600000", "settlement.recovery.interval-ms=3600000"})
    @AutoConfigureMockMvc
    class RelayAfterRequest {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper json;
        @Autowired OutboxRepository outbox;

        @Test
        void theEventsOfARequestArePublishedBeforeItEnds() throws Exception {
            UUID id = submit(mvc, json);
            List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(id);
            assertThat(events).isNotEmpty().allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        }
    }

    @Nested
    @SpringBootTest(properties = {"settlement.jobs.maintenance-endpoint=true",
            "settlement.outbox.relay-interval-ms=3600000", "settlement.recovery.interval-ms=3600000"})
    @AutoConfigureMockMvc
    class ScheduledMaintenance {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper json;
        @Autowired OutboxRepository outbox;

        @Test
        void theMaintenanceCallPublishesWhatTheOutboxStillHolds() throws Exception {
            UUID id = submit(mvc, json);
            assertThat(outbox.findByAggregateIdOrderByIdAsc(id)).anySatisfy(e -> assertThat(e.getPublishedAt()).isNull());
            MvcResult r = mvc.perform(post("/internal/jobs/maintenance")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(r.getResponse().getContentAsString()).get("relayed").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(outbox.findByAggregateIdOrderByIdAsc(id)).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class ElsewhereTheEndpointDoesNotExist {
        @Autowired MockMvc mvc;

        @Test
        void withoutTheSettingThereIsNoMaintenanceEndpoint() throws Exception {
            assertThat(mvc.perform(post("/internal/jobs/maintenance")).andReturn().getResponse().getStatus()).isEqualTo(404);
        }
    }
}
