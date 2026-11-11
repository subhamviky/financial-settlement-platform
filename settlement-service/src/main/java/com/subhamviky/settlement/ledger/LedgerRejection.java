package com.subhamviky.settlement.ledger;

/** A business reason the ledger refuses a posting (unknown account, insufficient funds, ...). */
public class LedgerRejection extends RuntimeException {
    public LedgerRejection(String message) {
        super(message);
    }
}
