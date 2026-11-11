package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.settlement.ledger.LedgerEntry;
import com.subhamviky.settlement.ledger.LedgerEntryRepository;
import com.subhamviky.settlement.ledger.LedgerService;
import com.subhamviky.settlement.outbox.OutboxEvent;
import com.subhamviky.settlement.outbox.OutboxRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Acceptance criteria AC-1 to AC-8 of settlement/REQUIREMENTS.md, end to end through the HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
class SettlementFlowTests {

    private static final String TENANT = "acme";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private LedgerService ledger;
    @Autowired private OutboxRepository outbox;

    private MvcResult submit(String key, String payer, String payee, String amount) throws Exception {
        String body = """
                {"payerAccount":"%s","payeeAccount":"%s","amount":%s,"currency":"EUR"}""".formatted(payer, payee, amount);
        return mvc.perform(post("/api/v1/settlements").header("X-Tenant-Id", TENANT).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private BigDecimal balance(String account) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/accounts/" + account + "/balance").header("X-Tenant-Id", TENANT)).andReturn();
        return body(r).get("balance").decimalValue();
    }

    private List<String> eventTypes(UUID settlementId) {
        return outbox.findByAggregateIdOrderByIdAsc(settlementId).stream().map(OutboxEvent::getEventType).toList();
    }

    @Test
    void ac1_aValidSettlementCompletesAndMovesMoneyOnce() throws Exception {
        BigDecimal payerBefore = balance("ACC-1000");
        BigDecimal payeeBefore = balance("ACC-2000");
        MvcResult r = submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-2000", "1250.00");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode s = body(r);
        assertThat(s.get("status").asText()).isEqualTo("COMPLETED");
        UUID id = UUID.fromString(s.get("settlementId").asText());
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore.subtract(new BigDecimal("1250.00")));
        assertThat(balance("ACC-2000")).isEqualByComparingTo(payeeBefore.add(new BigDecimal("1250.00")));
        assertThat(ledger.isBalanced(id)).isTrue();
        assertThat(entries.findBySettlementIdOrderByIdAsc(id)).hasSize(2);
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.completed");
    }

    @Test
    void ac2_retryWithTheSameKeyReplaysAndDoesNotPostTwice() throws Exception {
        String key = UUID.randomUUID().toString();
        BigDecimal payerBefore = balance("ACC-1000");
        MvcResult first = submit(key, "ACC-1000", "ACC-2000", "10.00");
        MvcResult second = submit(key, "ACC-1000", "ACC-2000", "10.00");
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(second).get("settlementId")).isEqualTo(body(first).get("settlementId"));
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore.subtract(new BigDecimal("10.00")));
    }

    @Test
    void ac3_sameKeyWithDifferentContentIsAConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        submit(key, "ACC-1000", "ACC-2000", "11.00");
        MvcResult conflict = submit(key, "ACC-1000", "ACC-2000", "12.00");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void ac4_thePolicyGateFailsClosedBeforeMoneyMoves() throws Exception {
        BigDecimal payerBefore = balance("ACC-1000");
        MvcResult overLimit = submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-2000", "2000000.00");
        assertThat(overLimit.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(overLimit).get("status").asText()).isEqualTo("REJECTED");
        assertThat(body(overLimit).get("reason").asText()).contains("exceeds the policy limit");
        MvcResult blocked = submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-BLOCKED", "5.00");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore);
        UUID id = UUID.fromString(body(overLimit).get("settlementId").asText());
        assertThat(entries.findBySettlementIdOrderByIdAsc(id)).isEmpty();
        assertThat(eventTypes(id)).containsExactly("settlement.rejected");
    }

    @Test
    void ac5_insufficientFundsIsRejectedWithoutPostings() throws Exception {
        MvcResult r = submit(UUID.randomUUID().toString(), "ACC-3000", "ACC-2000", "1.00");
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).get("reason").asText()).contains("insufficient funds");
        UUID id = UUID.fromString(body(r).get("settlementId").asText());
        assertThat(entries.findBySettlementIdOrderByIdAsc(id)).isEmpty();
    }

    @Test
    void ac6_aClearingDeclineIsCompensatedAndTheLedgerStaysBalanced() throws Exception {
        BigDecimal payerBefore = balance("ACC-1000");
        BigDecimal payeeBefore = balance("ACC-2000");
        MvcResult r = submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-2000", "300000.00");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(r).get("status").asText()).isEqualTo("COMPENSATED");
        UUID id = UUID.fromString(body(r).get("settlementId").asText());
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore);
        assertThat(balance("ACC-2000")).isEqualByComparingTo(payeeBefore);
        List<LedgerEntry> list = entries.findBySettlementIdOrderByIdAsc(id);
        assertThat(list).hasSize(4);
        assertThat(list.stream().filter(e -> e.getPostingType() == LedgerEntry.PostingType.REVERSAL)).hasSize(2);
        assertThat(ledger.isBalanced(id)).isTrue();
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.compensated");
    }

    @Test
    void ac7_theEventFeedReturnsContractEnvelopesInOrder() throws Exception {
        submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-2000", "1.00");                 // completed
        submit(UUID.randomUUID().toString(), "ACC-1000", "ACC-2000", "300000.00");             // compensated
        submit(UUID.randomUUID().toString(), "ACC-3000", "ACC-2000", "1.00");                 // rejected
        JsonNode feed = body(mvc.perform(get("/api/v1/events").param("afterSequence", "0").header("X-Tenant-Id", TENANT)).andReturn());
        JsonNode events = feed.get("events");
        assertThat(events.size()).isGreaterThan(0);
        long previous = 0;
        for (JsonNode item : events) {
            assertThat(item.get("sequence").asLong()).isGreaterThan(previous);
            previous = item.get("sequence").asLong();
            JsonNode e = item.get("event");
            for (String field : List.of("event_id", "event_type", "schema_version", "occurred_at", "tenant_id", "correlation_id", "settlement")) {
                assertThat(e.has(field)).as(field).isTrue();
            }
            assertThat(e.get("settlement").get("amount").isTextual()).isTrue();
        }
        assertThat(feed.get("next_after_sequence").asLong()).isEqualTo(previous);
        // Contract samples: CI validates these real events against contracts/events/settlement-event.v1.schema.json
        Path samples = Path.of("target", "contract-samples", "settlement-events.json");
        Files.createDirectories(samples.getParent());
        Files.writeString(samples, json.writerWithDefaultPrettyPrinter().writeValueAsString(events));
        JsonNode otherTenant = body(mvc.perform(get("/api/v1/events").header("X-Tenant-Id", "other")).andReturn());
        assertThat(otherTenant.get("events").size()).isZero();
    }

    @Test
    void ac8_requestsWithoutTheRequiredHeadersOrWithInvalidInputAreRefused() throws Exception {
        MvcResult noKey = mvc.perform(post("/api/v1/settlements").header("X-Tenant-Id", TENANT)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        MvcResult invalid = submit(UUID.randomUUID().toString(), "ACC-1000", "", "-5");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(invalid).get("errors").toString()).contains("amount").contains("payeeAccount");
        MvcResult unknown = mvc.perform(get("/api/v1/settlements/" + UUID.randomUUID()).header("X-Tenant-Id", TENANT)).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
    }
}
