package com.subhamviky.settlement.api;

import com.subhamviky.settlement.adjustment.PostAdjustmentCommand;
import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.e2a.cqrs.CqrsRequest;
import com.subhamviky.e2a.cqrs.CqrsResult;
import com.subhamviky.settlement.settlement.SettlementOrchestrator;
import com.subhamviky.settlement.settlement.SubmitSettlementCommand;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposure layer only: headers to CommandContext, then the orchestrator. */
@RestController
@RequestMapping("/api/v1")
public class SettlementController {

    private final SettlementOrchestrator orchestrator;

    public SettlementController(SettlementOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/settlements")
    public ResponseEntity<Object> submit(@RequestHeader("X-Tenant-Id") String tenantId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                         @RequestBody SubmitSettlementCommand command) {
        CommandContext ctx = new CommandContext(tenantId, idempotencyKey,
                correlationId != null ? correlationId : UUID.randomUUID().toString());
        return respond(orchestrator.execute(CqrsRequest.command(SettlementOrchestrator.SUBMIT, ctx, command)));
    }

    @GetMapping("/settlements/{id}")
    public ResponseEntity<Object> get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return respond(orchestrator.execute(CqrsRequest.query(SettlementOrchestrator.GET_SETTLEMENT,
                new CommandContext(tenantId, null, UUID.randomUUID().toString()), Map.of("id", id))));
    }

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<Object> balance(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return respond(orchestrator.execute(CqrsRequest.query(SettlementOrchestrator.GET_BALANCE,
                new CommandContext(tenantId, null, UUID.randomUUID().toString()), Map.of("id", id))));
    }

    /** Write-off or refund of a completed settlement (used by the reconciliation engine). */
    @PostMapping("/settlements/{id}/adjustments")
    public ResponseEntity<Object> adjust(@RequestHeader("X-Tenant-Id") String tenantId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                         @PathVariable UUID id, @RequestBody PostAdjustmentCommand command) {
        CommandContext ctx = new CommandContext(tenantId, idempotencyKey,
                correlationId != null ? correlationId : UUID.randomUUID().toString());
        return respond(orchestrator.execute(CqrsRequest.command(SettlementOrchestrator.ADJUST, ctx,
                command.withSettlementId(id))));
    }

    @GetMapping("/settlements/{id}/adjustments")
    public ResponseEntity<Object> adjustments(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return respond(orchestrator.execute(CqrsRequest.query(SettlementOrchestrator.GET_ADJUSTMENTS,
                new CommandContext(tenantId, null, UUID.randomUUID().toString()), Map.of("id", id))));
    }

    private static ResponseEntity<Object> respond(CqrsResult result) {
        return ResponseEntity.status(result.httpStatus())
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
