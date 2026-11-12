package com.subhamviky.settlement.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Double-entry ledger. Every posting writes one DEBIT and one CREDIT of the same
 * amount, so the debits and credits of a settlement always balance. Callers run
 * these methods inside their own transaction (see SettlementSaga).
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository entries;

    public LedgerService(AccountRepository accounts, LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    /** Debit the payer and credit the payee. Throws LedgerRejection for business refusals. */
    public void post(UUID settlementId, String tenantId, String payerId, String payeeId, BigDecimal amount, String currency) {
        Account payer = account(payerId, tenantId, currency, "payer");
        Account payee = account(payeeId, tenantId, currency, "payee");
        payer.debit(amount);
        payee.credit(amount);
        entries.save(new LedgerEntry(settlementId, payerId, LedgerEntry.Direction.DEBIT, amount, LedgerEntry.PostingType.POSTING));
        entries.save(new LedgerEntry(settlementId, payeeId, LedgerEntry.Direction.CREDIT, amount, LedgerEntry.PostingType.POSTING));
    }

    /** Compensation: book the opposite entries. Never fails on a funds check. */
    public void reverse(UUID settlementId, String tenantId, String payerId, String payeeId, BigDecimal amount, String currency) {
        Account payer = account(payerId, tenantId, currency, "payer");
        Account payee = account(payeeId, tenantId, currency, "payee");
        payee.debitForReversal(amount);
        payer.credit(amount);
        entries.save(new LedgerEntry(settlementId, payeeId, LedgerEntry.Direction.DEBIT, amount, LedgerEntry.PostingType.REVERSAL));
        entries.save(new LedgerEntry(settlementId, payerId, LedgerEntry.Direction.CREDIT, amount, LedgerEntry.PostingType.REVERSAL));
    }

    /**
     * Adjustment of a completed settlement (write-off or refund): debit one account, credit
     * another, same amount, booked against the settlement so its ledger stays balanced.
     * The debit is funds-checked like any posting.
     */
    public void adjust(UUID settlementId, String tenantId, String debitId, String creditId, BigDecimal amount,
                       String currency, String postingType) {
        Account debit = account(debitId, tenantId, currency, "debit");
        Account credit = account(creditId, tenantId, currency, "credit");
        LedgerEntry.PostingType type = LedgerEntry.PostingType.valueOf(postingType);
        debit.debit(amount);
        credit.credit(amount);
        entries.save(new LedgerEntry(settlementId, debitId, LedgerEntry.Direction.DEBIT, amount, type));
        entries.save(new LedgerEntry(settlementId, creditId, LedgerEntry.Direction.CREDIT, amount, type));
    }

    /** Ledger invariant: total debits equal total credits for the settlement. */
    public boolean isBalanced(UUID settlementId) {
        List<LedgerEntry> list = entries.findBySettlementIdOrderByIdAsc(settlementId);
        BigDecimal debits = sum(list, LedgerEntry.Direction.DEBIT);
        BigDecimal credits = sum(list, LedgerEntry.Direction.CREDIT);
        return debits.compareTo(credits) == 0;
    }

    private Account account(String id, String tenantId, String currency, String role) {
        Account account = accounts.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new LedgerRejection("unknown " + role + " account " + id));
        if (!account.getCurrency().equals(currency)) {
            throw new LedgerRejection(role + " account " + id + " is in " + account.getCurrency() + ", not " + currency);
        }
        return account;
    }

    private static BigDecimal sum(List<LedgerEntry> list, LedgerEntry.Direction direction) {
        return list.stream().filter(e -> e.getDirection() == direction)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
