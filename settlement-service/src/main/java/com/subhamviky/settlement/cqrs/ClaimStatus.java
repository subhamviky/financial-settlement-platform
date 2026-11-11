package com.subhamviky.settlement.cqrs;

/** Outcome of an idempotency claim; the same states as E2A's ClaimStatus. */
public enum ClaimStatus {
    CLAIMED,      // the caller owns the work: execute it
    REPLAY,       // already succeeded: return the stored result
    CONFLICT,     // same key, different request content
    IN_PROGRESS   // another caller holds the claim right now
}
