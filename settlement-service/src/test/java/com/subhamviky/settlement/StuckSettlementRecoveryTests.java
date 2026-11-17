package com.subhamviky.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.subhamviky.e2a.cqrs.CommandContext;
import com.subhamviky.settlement.ledger.AccountRepository;
import com.subhamviky.settlement.ledger.LedgerEntry;
import com.subhamviky.settlement.ledger.LedgerEntryRepository;
import com.subhamviky.settlement.ledger.LedgerService;
import com.subhamviky.settlement.outbox.OutboxEvent;
import com.subhamviky.settlement.outbox.OutboxRepository;
import com.subhamviky.settlement.outbox.OutboxWriter;
import com.subhamviky.settlement.settlement.Settlement;
import com.subhamviky.settlement.settlement.SettlementRepository;
import com.subhamviky.settlement.settlement.SettlementSaga;
import com.subhamviky.settlement.settlement.SettlementStatus;
import com.subhamviky.settlement.settlement.StuckSettlementRecovery;
import com.subhamviky.settlement.settlement.SubmitSettlementCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Backlog G-2: a settlement left in LEDGER_POSTED by a crash between T1 and T2/T3 is finished by recovery. */
@SpringBootTest
class StuckSettlementRecoveryTests {

    private static final String TENANT = "acme";

    @Autowired private PlatformTransactionManager txManager;
    @Autowired private SettlementRepository settlements;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerService ledger;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private OutboxWriter outboxWriter;
    @Autowired private OutboxRepository outbox;
    @Autowired private SettlementSaga saga;
    @Autowired private StuckSettlementRecovery recovery;

    /** T1 only, then "the process dies": what SettlementSaga.run commits before clearing. */
    private UUID postedThenCrashed(String amount) {
        return new TransactionTemplate(txManager).execute(status -> {
            Settlement s = settlements.save(Settlement.receive(TENANT,
                    new SubmitSettlementCommand("ACC-1000", "ACC-2000", new BigDecimal(amount), "EUR")));
            ledger.post(s.getId(), TENANT, "ACC-1000", "ACC-2000", s.getAmount(), "EUR");
            s.moveTo(SettlementStatus.LEDGER_POSTED);
            outboxWriter.write(OutboxWriter.LEDGER_POSTED, s, new CommandContext(TENANT, "k-" + s.getId(), "c-" + s.getId()));
            return s.getId();
        });
    }

    private BigDecimal balance(String account) {
        return accounts.findById(account).orElseThrow().getBalance();
    }

    private List<String> eventTypes(UUID id) {
        return outbox.findByAggregateIdOrderByIdAsc(id).stream().map(OutboxEvent::getEventType).toList();
    }

    private SettlementStatus status(UUID id) {
        return settlements.findById(id).orElseThrow().getStatus();
    }

    private static Instant soon() {
        return Instant.now().plusSeconds(1);
    }

    @Test
    void aStuckSettlementThatClearsIsCompletedOnceWithoutMovingMoneyAgain() {
        BigDecimal payerBefore = balance("ACC-1000");
        UUID id = postedThenCrashed("125.00");

        assertThat(recovery.recover(soon())).isGreaterThanOrEqualTo(1);

        assertThat(status(id)).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore.subtract(new BigDecimal("125.00")));
        assertThat(entries.findBySettlementIdOrderByIdAsc(id)).hasSize(2);
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.completed");
    }

    @Test
    void aStuckSettlementThatClearingDeclinesIsCompensatedAndTheLedgerBalances() {
        BigDecimal payerBefore = balance("ACC-1000");
        UUID id = postedThenCrashed("300000.00");                 // above the clearing limit

        recovery.recover(soon());

        Settlement s = settlements.findById(id).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.COMPENSATED);
        assertThat(s.getFailureReason()).isEqualTo("declined by clearing");
        assertThat(balance("ACC-1000")).isEqualByComparingTo(payerBefore);
        assertThat(entries.findBySettlementIdOrderByIdAsc(id).stream()
                .filter(e -> e.getPostingType() == LedgerEntry.PostingType.REVERSAL)).hasSize(2);
        assertThat(ledger.isBalanced(id)).isTrue();
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.compensated");
    }

    @Test
    void aRecentSettlementIsLeftToItsRequestAndASecondRunChangesNothing() {
        UUID id = postedThenCrashed("50.00");

        recovery.recover(Instant.now().minusSeconds(3600));      // not stale yet
        assertThat(status(id)).isEqualTo(SettlementStatus.LEDGER_POSTED);

        recovery.recover(soon());
        recovery.recover(soon());                                // nothing left to do
        assertThat(status(id)).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.completed");
    }

    @Test
    void theRequestFinishingAfterRecoveryReturnsTheOutcomeAndWritesNoSecondEvent() {
        UUID id = postedThenCrashed("75.00");
        recovery.recover(soon());                                // recovery finished it first

        Settlement late = saga.finish(id, new SettlementSaga.ClearingResult(false, "late request"),
                new CommandContext(TENANT, "k", "c"));

        assertThat(late.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(entries.findBySettlementIdOrderByIdAsc(id)).hasSize(2);
        assertThat(eventTypes(id)).containsExactly("ledger.posted", "settlement.completed");
    }
}
