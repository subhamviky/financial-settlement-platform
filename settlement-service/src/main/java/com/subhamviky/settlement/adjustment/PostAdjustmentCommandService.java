package com.subhamviky.settlement.adjustment;

import com.subhamviky.e2a.cqrs.BaseCommandService;
import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.e2a.cqrs.CqrsResult;
import com.subhamviky.settlement.ledger.LedgerRejection;
import com.subhamviky.settlement.ledger.LedgerService;
import com.subhamviky.settlement.outbox.OutboxWriter;
import com.subhamviky.settlement.settlement.Settlement;
import com.subhamviky.settlement.settlement.SettlementRepository;
import com.subhamviky.settlement.settlement.SettlementStatus;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write path for POST /api/v1/settlements/{id}/adjustments. The rules are this service's
 * own, whatever the caller already checked:
 *   - only a COMPLETED settlement of the caller's tenant can be adjusted;
 *   - write-offs on one settlement add up to at most the write-off limit;
 *   - refunds on one settlement add up to at most its amount.
 * The settlement row is locked with a forced version increment, so two concurrent
 * adjustments of the same settlement cannot both pass the limit check (one gets 409).
 * The adjustment, its two ledger entries and the outbox event commit in one transaction.
 */
@Service
public class PostAdjustmentCommandService extends BaseCommandService<PostAdjustmentCommand> {

    private final TransactionTemplate tx;
    private final SettlementRepository settlements;
    private final LedgerAdjustmentRepository adjustments;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final BigDecimal writeOffLimit;
    private final String writeOffAccount;

    public PostAdjustmentCommandService(Validator validator, PlatformTransactionManager txManager,
                                        SettlementRepository settlements, LedgerAdjustmentRepository adjustments,
                                        LedgerService ledger, OutboxWriter outbox,
                                        @Value("${settlement.adjustments.write-off-limit}") BigDecimal writeOffLimit,
                                        @Value("${settlement.adjustments.write-off-account}") String writeOffAccount) {
        super(validator, 2_500);
        this.tx = new TransactionTemplate(txManager);
        this.settlements = settlements;
        this.adjustments = adjustments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.writeOffLimit = writeOffLimit;
        this.writeOffAccount = writeOffAccount;
    }

    @Override
    protected CqrsResult executeBusinessLogic(PostAdjustmentCommand command, CommandContext ctx) {
        try {
            return tx.execute(status -> post(command, ctx));
        } catch (LedgerRejection refused) {               // rolled back: nothing was posted
            return CqrsResult.error(422, refused.getMessage());
        }
    }

    private CqrsResult post(PostAdjustmentCommand command, CommandContext ctx) {
        Optional<Settlement> found = settlements.findForUpdateByIdAndTenantId(command.settlementId(), ctx.tenantId());
        if (found.isEmpty()) {
            return CqrsResult.error(404, "unknown settlement " + command.settlementId());
        }
        Settlement s = found.get();
        if (s.getStatus() != SettlementStatus.COMPLETED) {
            throw new LedgerRejection("only a COMPLETED settlement can be adjusted; this one is " + s.getStatus());
        }
        BigDecimal already = adjustments.findByTenantIdAndSettlementIdOrderByCreatedAtAsc(ctx.tenantId(), s.getId())
                .stream().filter(a -> a.getType() == command.type())
                .map(LedgerAdjustment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ceiling = command.type() == AdjustmentType.WRITE_OFF ? writeOffLimit : s.getAmount();
        if (already.add(command.amount()).compareTo(ceiling) > 0) {
            throw new LedgerRejection("%s of %s on top of %s exceeds %s %s".formatted(
                    command.type(), command.amount(), already,
                    command.type() == AdjustmentType.WRITE_OFF ? "the write-off limit" : "the settled amount", ceiling));
        }
        String debit = s.getPayeeAccount();
        String credit = command.type() == AdjustmentType.WRITE_OFF ? writeOffAccount : s.getPayerAccount();
        LedgerAdjustment adjustment = adjustments.save(new LedgerAdjustment(ctx.tenantId(), command, debit, credit));
        ledger.adjust(s.getId(), ctx.tenantId(), debit, credit, command.amount(), s.getCurrency(), command.type().name());
        outbox.writeAdjustment(s, adjustment, ctx);
        return CqrsResult.of(201, AdjustmentView.of(adjustment, s.getCurrency()));
    }
}
