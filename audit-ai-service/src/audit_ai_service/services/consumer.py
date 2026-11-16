"""Settlement events under E2A's IdempotentConsumer (E2A v1.7.x, hub design note MP-05).

Every settlement event, whatever path it comes by, is settled by one consumer under one E2A
claim on its event_id (Cosmos DB in Azure, e2a-adapters):

    Service Bus queue      settlement-service's outbox relay sends each event to audit-ai's
                           queue; AuditService.consume() receives, settles and acknowledges.
                           Poison messages go to the queue's dead-letter subqueue with the reason.
    event feed (HTTP)      AuditService.poll_once() reads settlement-service's feed, a catch-up
                           path: process() settles the event through the same consumer, so an
                           event seen on both paths is audited once.
    dead letters           AuditService.reconcile_dead_letters(): each one is confirmed first
                           (a finding exists?) and replayed only if it never took effect.

The handler is resumable: recording a finding is a create-if-absent and the index append is
idempotent, so a redelivery may take over a claim whose replica stopped.
"""
from typing import Any, Dict

from e2a import IdempotentConsumer, PhysicalOutcome

from ..models.events import SettlementEvent

AUDITED_TYPES = ("settlement.completed", "settlement.compensated")

CONSUMER_CONFIG = {"consumer_resume_expired": True, "idempotency_lease_seconds": 120,
                   "consumer_retry_delay_seconds": 0}


class SettlementEventConsumer(IdempotentConsumer):
    def __init__(self, service, broker, source: str = None, lock=None) -> None:
        super().__init__(broker, service.store, source, lock)
        self.service = service

    def _tenant_of(self, message):
        tenant = message.body.get("tenant_id")
        sent_for = message.attributes.get("tenant_id")
        if sent_for and tenant and sent_for != tenant:
            return None                         # attribute and envelope disagree: dead-lettered
        return tenant

    def _idempotency_key(self, message):
        if message.attributes.get("e2a_body_error"):
            return None
        return message.body.get("event_id")

    def _handle(self, message, config, tenant_id, idempotency_key) -> Dict[str, Any]:
        # Tolerant reader: an event type this service does not audit is acknowledged unread.
        if message.body.get("event_type") not in AUDITED_TYPES:
            return {"physical_success": True, "skipped": message.body.get("event_type")}
        event = SettlementEvent.model_validate(message.body)   # a ValidationError is a ValueError
        finding = self.service.audit_and_record(event)
        return {"physical_success": True, "finding": finding.model_dump(mode="json")}

    def _confirm(self, message, config) -> PhysicalOutcome:
        """A dead letter took effect if its finding is recorded."""
        event_id = message.body.get("event_id")
        if event_id and self.service.finding(event_id) is not None:
            return PhysicalOutcome.COMMITTED
        return PhysicalOutcome.NOT_COMMITTED
