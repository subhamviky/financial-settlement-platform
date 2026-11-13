package com.subhamviky.settlement.outbox;

import com.subhamviky.settlement.adjustment.LedgerAdjustment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.settlement.settlement.Settlement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the event envelope defined by contracts/events/settlement-event.v1.schema.json
 * and stores it in the outbox. Call it inside the transaction that changes the settlement.
 */
@Component
public class OutboxWriter {

    public static final String LEDGER_POSTED = "ledger.posted";
    public static final String SETTLEMENT_COMPLETED = "settlement.completed";
    public static final String SETTLEMENT_COMPENSATED = "settlement.compensated";
    public static final String SETTLEMENT_REJECTED = "settlement.rejected";
    public static final String LEDGER_ADJUSTED = "ledger.adjusted";

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void write(String eventType, Settlement s, CommandContext ctx) {
        write(eventType, s, null, ctx);
    }

    /** ledger.adjusted: the settlement as it stands plus the adjustment (schema v1, additive). */
    public void writeAdjustment(Settlement s, LedgerAdjustment a, CommandContext ctx) {
        Map<String, Object> adjustment = new LinkedHashMap<>();
        adjustment.put("adjustment_id", a.getId().toString());
        adjustment.put("type", a.getType().name());
        adjustment.put("amount", a.getAmount().toPlainString());
        adjustment.put("debit_account", a.getDebitAccount());
        adjustment.put("credit_account", a.getCreditAccount());
        adjustment.put("reason", a.getReason());
        adjustment.put("reference", a.getReference());
        write(LEDGER_ADJUSTED, s, adjustment, ctx);
    }

    private void write(String eventType, Settlement s, Map<String, Object> adjustment, CommandContext ctx) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("settlement_id", s.getId().toString());
        settlement.put("payer_account", s.getPayerAccount());
        settlement.put("payee_account", s.getPayeeAccount());
        settlement.put("amount", s.getAmount().toPlainString());
        settlement.put("currency", s.getCurrency());
        settlement.put("status", s.getStatus().name());
        settlement.put("reason", s.getFailureReason());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", eventType);
        envelope.put("schema_version", "1");
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("tenant_id", ctx.tenantId());
        envelope.put("correlation_id", ctx.correlationId());
        envelope.put("settlement", settlement);
        if (adjustment != null) {
            envelope.put("adjustment", adjustment);
        }
        try {
            outbox.save(new OutboxEvent(eventId, ctx.tenantId(), s.getId(), eventType, json.writeValueAsString(envelope)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
