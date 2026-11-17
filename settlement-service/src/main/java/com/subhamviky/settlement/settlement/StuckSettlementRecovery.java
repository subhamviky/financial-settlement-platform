package com.subhamviky.settlement.settlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subhamviky.e2a.core.IdempotencyStore;
import com.subhamviky.e2a.cqrs.CommandContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes settlements that a crash left in LEDGER_POSTED (backlog G-2).
 *
 * T1 has moved the money; T2 (COMPLETED) or T3 (reversal, COMPENSATED) never ran. On a schedule,
 * every settlement in LEDGER_POSTED for longer than {@code settlement.recovery.stale-after} is asked
 * of clearing again (the gateway answers the same for the same settlement) and finished through the
 * saga's own {@link SettlementSaga#finish}. The answer decides, as it would have in the request.
 *
 * Safe with several replicas and with a request still running: finish() re-reads the settlement,
 * does nothing when it is no longer LEDGER_POSTED, and the @Version check lets only one writer
 * commit. Once finished, the client's idempotency record (still IN_PROGRESS after the crash) is
 * completed with the outcome, so a retry with the same Idempotency-Key replays it instead of
 * getting 409 "still in progress" for ever. The stale-after period is far longer than a request's clearing retries, so a running
 * request normally finishes first.
 */
@Component
public class StuckSettlementRecovery {

    private static final Logger log = LoggerFactory.getLogger(StuckSettlementRecovery.class);

    private final SettlementRepository settlements;
    private final SettlementSaga saga;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;
    private final Duration staleAfter;

    public StuckSettlementRecovery(SettlementRepository settlements, SettlementSaga saga, IdempotencyStore idempotency,
                                   ObjectMapper json,
                                   @Value("${settlement.recovery.stale-after:PT2M}") Duration staleAfter) {
        this.settlements = settlements;
        this.saga = saga;
        this.idempotency = idempotency;
        this.json = json;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${settlement.recovery.interval-ms:30000}",
               initialDelayString = "${settlement.recovery.interval-ms:30000}")
    public void onSchedule() {
        int finished = recover(Instant.now().minus(staleAfter));
        if (finished > 0) {
            log.info("stuck-settlement recovery finished {} settlement(s)", finished);
        }
    }

    /** Finishes the settlements left in LEDGER_POSTED since before {@code cutoff}; returns how many are no longer stuck. */
    public int recover(Instant cutoff) {
        List<Settlement> stuck = settlements.findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                SettlementStatus.LEDGER_POSTED, cutoff);
        int finished = 0;
        for (Settlement s : stuck) {
            try {
                CommandContext ctx = new CommandContext(s.getTenantId(), s.getIdempotencyKey(), "recovery-" + s.getId());
                Settlement after = saga.finish(s.getId(), saga.clear(s), ctx);
                if (after.getStatus() != SettlementStatus.LEDGER_POSTED) {
                    completeClientRecord(after);
                    finished++;
                    log.warn("recovered settlement {} (tenant {}): {}", s.getId(), s.getTenantId(), after.getStatus());
                }
            } catch (RuntimeException e) {
                log.error("recovery of settlement {} failed; it stays LEDGER_POSTED for the next run", s.getId(), e);
            }
        }
        return finished;
    }

    /** What the request would have stored: 201 and the settlement view, as the orchestrator writes it. */
    private void completeClientRecord(Settlement s) {
        if (s.getIdempotencyKey() == null) {
            return;
        }
        try {
            idempotency.complete(s.getTenantId(), s.getIdempotencyKey(), 201, json.writeValueAsString(SettlementView.of(s)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
