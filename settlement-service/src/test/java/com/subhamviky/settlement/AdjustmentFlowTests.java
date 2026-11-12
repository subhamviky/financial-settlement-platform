package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/** Ledger adjustments (AC-18 to AC-22): write-offs and refunds of completed settlements. */
@SpringBootTest
@AutoConfigureMockMvc
class AdjustmentFlowTests {

    private static final String TENANT = "acme";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private LedgerService ledger;
    @Autowired private OutboxRepository outbox;

    private JsonNode settle(String payer, String payee, String amount) throws Exception {
        String body = """
                {"payerAccount":"%s","payeeAccount":"%s","amount":%s,"currency":"EUR"}""".formatted(payer, payee, amount);
        MvcResult r = mvc.perform(post("/api/v1/settlements").header("X-Tenant-Id", TENANT)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private MvcResult adjust(String settlementId, String key, String type, String amount) throws Exception {
        String body = """
                {"type":"%s","amount":%s,"reason":"test %s","reference":"BANK-%s"}""".formatted(type, amount, type, key);
        return mvc.perform(post("/api/v1/settlements/" + settlementId + "/adjustments").header("X-Tenant-Id", TENANT)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private BigDecimal balance(String account) throws Exception {
        return body(mvc.perform(get("/api/v1/accounts/" + account + "/balance").header("X-Tenant-Id", TENANT)).andReturn())
                .get("balance").decimalValue();
    }

    private int adjustmentCount(String settlementId) throws Exception {
        return body(mvc.perform(get("/api/v1/settlements/" + settlementId + "/adjustments")
                .header("X-Tenant-Id", TENANT)).andReturn()).size();
    }

    @Test
    void ac18_aWriteOffMovesTheDifferenceToTheWriteOffAccount() throws Exception {
        String id = settle("ACC-1000", "ACC-2000", "100.00").get("settlementId").asText();
        BigDecimal payeeBefore = balance("ACC-2000");
        BigDecimal writeOffBefore = balance("ACC-WRITEOFF");
        MvcResult r = adjust(id, UUID.randomUUID().toString(), "WRITE_OFF", "2.50");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode a = body(r);
        assertThat(a.get("debitAccount").asText()).isEqualTo("ACC-2000");
        assertThat(a.get("creditAccount").asText()).isEqualTo("ACC-WRITEOFF");
        assertThat(balance("ACC-2000")).isEqualByComparingTo(payeeBefore.subtract(new BigDecimal("2.50")));
        assertThat(balance("ACC-WRITEOFF")).isEqualByComparingTo(writeOffBefore.add(new BigDecimal("2.50")));
        UUID uuid = UUID.fromString(id);
        assertThat(entries.findBySettlementIdOrderByIdAsc(uuid)).hasSize(4);
        assertThat(ledger.isBalanced(uuid)).isTrue();
        List<OutboxEvent> events = outbox.findByAggregateIdOrderByIdAsc(uuid);
        assertThat(events.stream().map(OutboxEvent::getEventType).toList())
                .containsExactly("ledger.posted", "settlement.completed", "ledger.adjusted");
        assertThat(adjustmentCount(id)).isEqualTo(1);

        // the real ledger.adjusted event, for CI's contract check
        ArrayNode samples = json.createArrayNode();
        for (OutboxEvent e : events) {
            samples.add(json.readTree(e.getPayload()));
        }
        Path file = Path.of("target", "contract-samples", "adjustment-events.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(samples));
    }

    @Test
    void ac19_theSameKeyReplaysAndDoesNotPostTwice() throws Exception {
        String id = settle("ACC-1000", "ACC-2000", "50.00").get("settlementId").asText();
        String key = UUID.randomUUID().toString();
        BigDecimal before = balance("ACC-WRITEOFF");
        MvcResult first = adjust(id, key, "WRITE_OFF", "1.00");
        MvcResult again = adjust(id, key, "WRITE_OFF", "1.00");
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(again).get("adjustmentId")).isEqualTo(body(first).get("adjustmentId"));
        assertThat(balance("ACC-WRITEOFF")).isEqualByComparingTo(before.add(new BigDecimal("1.00")));
        assertThat(adjustmentCount(id)).isEqualTo(1);
    }

    @Test
    void ac20_writeOffsOfOneSettlementStayWithinTheLimit() throws Exception {
        String id = settle("ACC-1000", "ACC-2000", "500.00").get("settlementId").asText();
        assertThat(adjust(id, UUID.randomUUID().toString(), "WRITE_OFF", "3.00").getResponse().getStatus()).isEqualTo(201);
        BigDecimal before = balance("ACC-WRITEOFF");
        MvcResult over = adjust(id, UUID.randomUUID().toString(), "WRITE_OFF", "2.50");
        assertThat(over.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(over).get("error").asText()).contains("write-off limit");
        assertThat(balance("ACC-WRITEOFF")).isEqualByComparingTo(before);
        assertThat(adjustmentCount(id)).isEqualTo(1);
        assertThat(ledger.isBalanced(UUID.fromString(id))).isTrue();
    }

    @Test
    void ac21_aRefundReturnsMoneyToThePayerUpToTheSettledAmount() throws Exception {
        String id = settle("ACC-1000", "ACC-2000", "80.00").get("settlementId").asText();
        BigDecimal payerBefore = balance("ACC-1000");
        BigDecimal payeeBefore = balance("ACC-2000");
        MvcResult refund = adjust(id, UUID.randomUUID().toString(), "REFUND", "80.00");
        assertThat(refund.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(refund).get("creditAccount").asText()).isEqualTo("ACC-1000");
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore.add(new BigDecimal("80.00")));
        assertThat(balance("ACC-2000")).isEqualByComparingTo(payeeBefore.subtract(new BigDecimal("80.00")));
        assertThat(adjust(id, UUID.randomUUID().toString(), "REFUND", "0.01").getResponse().getStatus()).isEqualTo(422);
        assertThat(ledger.isBalanced(UUID.fromString(id))).isTrue();
    }

    @Test
    void ac22_onlyCompletedSettlementsOfTheTenantCanBeAdjusted() throws Exception {
        String compensated = settle("ACC-1000", "ACC-2000", "300000.00").get("settlementId").asText();
        MvcResult notCompleted = adjust(compensated, UUID.randomUUID().toString(), "WRITE_OFF", "1.00");
        assertThat(notCompleted.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(notCompleted).get("error").asText()).contains("COMPENSATED");
        assertThat(adjust(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "WRITE_OFF", "1.00")
                .getResponse().getStatus()).isEqualTo(404);
        String completed = settle("ACC-1000", "ACC-2000", "10.00").get("settlementId").asText();
        assertThat(adjust(completed, UUID.randomUUID().toString(), "WRITE_OFF", "0").getResponse().getStatus())
                .isEqualTo(400);
        MvcResult otherTenant = mvc.perform(get("/api/v1/settlements/" + completed + "/adjustments")
                .header("X-Tenant-Id", "other")).andReturn();
        assertThat(otherTenant.getResponse().getStatus()).isEqualTo(404);
    }
}
