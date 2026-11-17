package com.subhamviky.settlement.settlement;

import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.settlement.generated.NfrSupport;
import com.subhamviky.settlement.ledger.LedgerRejection;
import com.subhamviky.settlement.ledger.LedgerService;
import com.subhamviky.settlement.outbox.OutboxWriter;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrated saga with compensation. Each step is its own local transaction,
 * and each transaction writes its outbox event together with the state change:
 *
 *   T1  RECEIVED -> LEDGER_POSTED  (debit payer, credit payee)   | or REJECTED
 *   --  clearing confirmation      (outside any transaction; retry + circuit breaker)
 *   T2  LEDGER_POSTED -> COMPLETED                               | or
 *   T3  LEDGER_POSTED -> COMPENSATING -> COMPENSATED  (reversal entries)
 *
 * A crash between T1 and T2/T3 leaves the settlement in LEDGER_POSTED; StuckSettlementRecovery
 * finishes it later through the same clear() and finish().
 */
@Component
public class SettlementSaga {

    private final TransactionTemplate tx;
    private final SettlementRepository settlements;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final ClearingGateway clearing;
    private final NfrSupport.CircuitBreaker clearingBreaker = new NfrSupport.CircuitBreaker(5, 30_000);

    public SettlementSaga(PlatformTransactionManager txManager, SettlementRepository settlements, LedgerService ledger,
                          OutboxWriter outbox, ClearingGateway clearing) {
        this.tx = new TransactionTemplate(txManager);
        this.settlements = settlements;
        this.ledger = ledger;
        this.outbox = outbox;
        this.clearing = clearing;
    }

    /** Records a settlement the policy gate refused. No money moves. */
    public Settlement reject(SubmitSettlementCommand command, CommandContext ctx, String reason) {
        return tx.execute(status -> {
            Settlement received = Settlement.receive(ctx.tenantId(), command, ctx.idempotencyKey());
            received.moveTo(SettlementStatus.REJECTED, reason);
            Settlement s = settlements.save(received);
            outbox.write(OutboxWriter.SETTLEMENT_REJECTED, s, ctx);
            return s;
        });
    }

    public Settlement run(SubmitSettlementCommand command, CommandContext ctx) {
        Settlement posted = tx.execute(status -> {
            // save() merges an entity whose id is already set and returns the MANAGED copy:
            // all further changes must go to that returned instance.
            Settlement s = settlements.save(Settlement.receive(ctx.tenantId(), command, ctx.idempotencyKey()));
            try {
                ledger.post(s.getId(), s.getTenantId(), s.getPayerAccount(), s.getPayeeAccount(), s.getAmount(), s.getCurrency());
                s.moveTo(SettlementStatus.LEDGER_POSTED);
                outbox.write(OutboxWriter.LEDGER_POSTED, s, ctx);
            } catch (LedgerRejection rejection) {
                s.moveTo(SettlementStatus.REJECTED, rejection.getMessage());
                outbox.write(OutboxWriter.SETTLEMENT_REJECTED, s, ctx);
            }
            return s;
        });
        if (posted.getStatus() == SettlementStatus.REJECTED) {
            return posted;
        }

        return finish(posted.getId(), clear(posted), ctx);
    }

    /** The clearing decision for a posted settlement: retried, behind the circuit breaker. */
    public ClearingResult clear(Settlement posted) {
        try {
            boolean cleared = NfrSupport.retryWithBackoff(() -> clearing.confirm(posted), clearingBreaker, 2, 50);
            return new ClearingResult(cleared, cleared ? null : "declined by clearing");
        } catch (RuntimeException unavailable) {
            return new ClearingResult(false, "clearing unavailable: " + unavailable.getMessage());
        }
    }

    /**
     * T2 or T3 for a settlement in LEDGER_POSTED. Used by the request that posted it and by
     * {@link StuckSettlementRecovery}. Whichever comes second finds the settlement already
     * finished (or loses the optimistic lock) and returns it as it stands: one outcome, one event.
     */
    public Settlement finish(UUID id, ClearingResult result, CommandContext ctx) {
        try {
            return tx.execute(status -> {
                Settlement s = settlements.findById(id).orElseThrow();
                if (s.getStatus() != SettlementStatus.LEDGER_POSTED) {
                    return s;                                   // already finished by the other path
                }
                if (result.cleared()) {
                    s.moveTo(SettlementStatus.COMPLETED);
                    outbox.write(OutboxWriter.SETTLEMENT_COMPLETED, s, ctx);
                    return s;
                }
                s.moveTo(SettlementStatus.COMPENSATING);
                ledger.reverse(s.getId(), s.getTenantId(), s.getPayerAccount(), s.getPayeeAccount(), s.getAmount(), s.getCurrency());
                s.moveTo(SettlementStatus.COMPENSATED, result.reason());
                outbox.write(OutboxWriter.SETTLEMENT_COMPENSATED, s, ctx);
                return s;
            });
        } catch (ObjectOptimisticLockingFailureException concurrent) {
            return settlements.findById(id).orElseThrow();      // the other path committed first
        }
    }

    /** The clearing decision: cleared, or not with the reason recorded on the compensation. */
    public record ClearingResult(boolean cleared, String reason) {
    }
}
