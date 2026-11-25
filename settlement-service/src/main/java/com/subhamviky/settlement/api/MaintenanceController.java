package com.subhamviky.settlement.api;

import com.subhamviky.settlement.outbox.OutboxRelay;
import com.subhamviky.settlement.settlement.StuckSettlementRecovery;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google Cloud (hub design note GCP-01): what the scheduled timers do, as one call for Cloud
 * Scheduler: publish what the outbox still holds, then finish settlements left in LEDGER_POSTED.
 * Cloud Run scales to zero, so the timers do not run between requests. Only Cloud Scheduler's
 * service account may invoke it (Cloud Run IAM, infra/gcp); the path is outside /api/. Enabled by
 * settlement.jobs.maintenance-endpoint=true.
 */
@RestController
@ConditionalOnProperty(prefix = "settlement.jobs", name = "maintenance-endpoint", havingValue = "true")
public class MaintenanceController {

    private final OutboxRelay relay;
    private final StuckSettlementRecovery recovery;
    private final Duration staleAfter;

    public MaintenanceController(OutboxRelay relay, StuckSettlementRecovery recovery,
                                 @Value("${settlement.recovery.stale-after:PT2M}") Duration staleAfter) {
        this.relay = relay;
        this.recovery = recovery;
        this.staleAfter = staleAfter;
    }

    @PostMapping("/internal/jobs/maintenance")
    public Map<String, Integer> maintenance() {
        int relayed = 0;
        for (int batch = relay.relay(); batch > 0 && relayed < 1000; batch = relay.relay()) {
            relayed += batch;
        }
        int recovered = recovery.recover(Instant.now().minus(staleAfter));
        return Map.of("relayed", relayed, "recovered", recovered);
    }
}
