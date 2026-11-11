package com.subhamviky.settlement.cqrs;

import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E2A BaseIdempotencyStore realised on the database: the insert of the
 * (tenant, key) row is the atomic claim, so two concurrent callers can never
 * both execute the same request. Every call runs in its own transaction.
 */
@Component
public class JpaIdempotencyStore {

    /** Result of {@link #claim}; {@code record} is set for REPLAY. */
    public record Claim(ClaimStatus status, IdempotencyRecord record) {
    }

    private final IdempotencyRecordRepository repository;
    private final TransactionTemplate tx;

    public JpaIdempotencyStore(IdempotencyRecordRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Claim claim(String tenantId, String key, String fingerprint) {
        IdempotencyRecord.Key id = new IdempotencyRecord.Key(tenantId, key);
        try {
            tx.executeWithoutResult(s -> repository.saveAndFlush(new IdempotencyRecord(tenantId, key, fingerprint)));
            return new Claim(ClaimStatus.CLAIMED, null);
        } catch (DataIntegrityViolationException alreadyThere) {
            // fall through: someone claimed this key before us
        }
        return tx.execute(s -> {
            Optional<IdempotencyRecord> found = repository.findById(id);
            if (found.isEmpty()) {
                return new Claim(ClaimStatus.IN_PROGRESS, null);
            }
            IdempotencyRecord existing = found.get();
            if (!existing.getFingerprint().equals(fingerprint)) {
                return new Claim(ClaimStatus.CONFLICT, null);
            }
            return switch (existing.getStatus()) {
                case SUCCEEDED -> new Claim(ClaimStatus.REPLAY, existing);
                case IN_PROGRESS -> new Claim(ClaimStatus.IN_PROGRESS, null);
                case FAILED -> {
                    existing.reclaim();                 // a retryable failure may be claimed again
                    repository.save(existing);
                    yield new Claim(ClaimStatus.CLAIMED, null);
                }
            };
        });
    }

    public void complete(String tenantId, String key, int httpStatus, String responseBody) {
        tx.executeWithoutResult(s -> repository.findById(new IdempotencyRecord.Key(tenantId, key))
                .ifPresent(r -> {
                    r.succeed(httpStatus, responseBody);
                    repository.save(r);
                }));
    }

    public void fail(String tenantId, String key) {
        tx.executeWithoutResult(s -> repository.findById(new IdempotencyRecord.Key(tenantId, key))
                .ifPresent(r -> {
                    r.fail();
                    repository.save(r);
                }));
    }
}
