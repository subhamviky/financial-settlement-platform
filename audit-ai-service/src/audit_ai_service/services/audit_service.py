"""Audit pipeline: de-duplicate -> deterministic rules -> confirm against the
system of record -> retrieve policies -> agent explanation -> record.

A finding is FLAGGED only after the settlement is confirmed in settlement-service
(same status and amount): the agent may explain, but deterministic controls
decide and confirm (E2A).

The claims, the findings and the feed position live in E2A's ports (services/state.py): in
memory by default, or in Cosmos DB (e2a-adapters, design note D-03), shared by every Container
App replica, so an event is audited once and every replica lists the same findings.
"""
from decimal import Decimal
from typing import Dict, List, Optional, Tuple

from e2a import BaseDistributedLock, BaseIdempotencyStore, BaseMessageBroker, BaseTransactionalStore, InMemoryBroker
from e2a_simulated_llm import SimulatedLLMService

from ..agents.settlement_audit_agent import SettlementAuditAgent
from ..models.events import AuditFinding, SettlementEvent
from .policy_rag import PolicyRAGPipeline
from .risk_rules import RiskRules
from .paged_index import PagedIndex
from .settlement_client import SettlementSource, events_from
from .consumer import AUDITED_TYPES, CONSUMER_CONFIG, SettlementEventConsumer
from .state import state_ports


class AuditService:
    def __init__(self, source: SettlementSource, rag: PolicyRAGPipeline, llm: SimulatedLLMService,
                 tenant_id: str, rules: RiskRules = None, store: BaseIdempotencyStore = None,
                 rows: BaseTransactionalStore = None, broker: BaseMessageBroker = None,
                 queue: str = None, lease: BaseDistributedLock = None) -> None:
        self.source, self.rag, self.llm, self.tenant_id = source, rag, llm, tenant_id
        self.rules = rules or RiskRules()
        if store is None or rows is None:
            default_rows, default_store = state_ports()
            store, rows = store or default_store, rows or default_rows
        self.store, self.rows = store, rows
        self.agent = SettlementAuditAgent(rag)
        # Service Bus (services/state.py): settlement-service's events arrive on this queue
        self.broker, self.queue, self.lease = broker, queue, lease

    # ----------------------------------------------------------------- state
    @property
    def position(self) -> int:
        """The feed position, shared by every replica."""
        return int((self.rows.get(f"cursor:{self.tenant_id}") or {"position": 0})["position"])

    def _advance(self, new_position: int) -> None:
        """Move the cursor forward only (compare-and-set): a slower replica never moves it back."""
        key = f"cursor:{self.tenant_id}"
        while True:
            value, version = self.rows.get_versioned(key) or ({"position": 0}, None)
            if new_position <= value["position"]:
                return
            if self.rows.put_if_version(key, {"position": new_position}, version).committed:
                return

    def _record(self, finding: AuditFinding, tenant_id: str) -> None:
        """Store the finding once and list it in the tenant's paged index (compare-and-set)."""
        self.rows.put_if_version(f"finding:{finding.event_id}", finding.model_dump(mode="json"), None)
        PagedIndex(self.rows, f"findings:{tenant_id}").append(finding.event_id)

    # ------------------------------------------------------------------ feed
    def poll_once(self) -> int:
        start = self.position
        feed = self.source.events_after(self.tenant_id, start)
        processed = 0
        for raw in events_from(feed):
            # Tolerant reader: an event type this service does not audit is skipped before it is
            # parsed, so a type added to the contract later never stops the feed.
            if raw.get("event_type") not in AUDITED_TYPES:
                continue
            if self.process_raw(raw) is not None:
                processed += 1
        self._advance(int(feed.get("next_after_sequence", start)))
        return processed

    # ------------------------------------------------------------- the queue
    def consume(self, wait_seconds: float = 0.0) -> Dict:
        """Receive one batch from the Service Bus queue and settle every message (E2A consumer)."""
        if self.broker is None:
            return {"status": "DISABLED", "messages": []}
        config = {**CONSUMER_CONFIG, "consumer_wait_seconds": wait_seconds}
        return SettlementEventConsumer(self, self.broker, self.queue).consume(self.queue, config)

    def consume_push(self, envelope: Dict) -> Tuple[int, Dict]:
        """Google Cloud (GCP-01): settle one event Pub/Sub pushed (PubSubPushDelivery, E2A v1.10.0)
        with the same consumer and claim as every other path. Returns (HTTP status, report): 204
        when done (audited, a duplicate, or dead-lettered with its reason), 500 to be delivered again."""
        if self.broker is None:
            raise RuntimeError("no Pub/Sub broker configured (AUDIT_PUBSUB_SUBSCRIPTION, GOOGLE_CLOUD_PROJECT)")
        from e2a_adapters.gcp import PubSubPushDelivery
        delivery = PubSubPushDelivery(envelope, self.broker)          # ValueError: not a push envelope
        report = SettlementEventConsumer(self, delivery, delivery.source).consume(delivery.source, CONSUMER_CONFIG)
        return delivery.response_status(), report

    def reconcile_dead_letters(self) -> Dict:
        """Settle the queue's dead letters under the lease: confirmed ones are removed, never-applied
        ones replayed, poison ones left for a person (E2A BaseReconciliationService)."""
        if self.broker is None:
            return {"status": "DISABLED"}
        return SettlementEventConsumer(self, self.broker, self.queue, self.lease).reconcile(
            CONSUMER_CONFIG, dlq_source=self.queue)

    # --------------------------------------------------------------- one event
    def process(self, event: SettlementEvent) -> Optional[AuditFinding]:
        return self.process_raw(event.model_dump(mode="json"))

    def process_raw(self, raw: Dict) -> Optional[AuditFinding]:
        """Audit one event once (the feed path). It is settled by the same consumer as a queue
        delivery, under the same claim, through an in-process broker. The body is the event's JSON
        as settlement-service published it, so the claim's fingerprint matches the queue's copy.
        Returns the new finding, or None when the event was already handled or is not audited."""
        if raw.get("event_type") not in AUDITED_TYPES:
            return None
        broker = InMemoryBroker()
        broker.publish("feed", raw, {"tenant_id": str(raw.get("tenant_id"))})
        settled = SettlementEventConsumer(self, broker, "feed").settle(broker.receive("feed")[0], "feed",
                                                                      CONSUMER_CONFIG)
        if settled["decision"] == "processed":
            return AuditFinding.model_validate(dict(settled["result"]["finding"]))
        if settled["error"] is not None:
            raise settled["error"]
        return None                                       # duplicate or in progress elsewhere

    def audit_and_record(self, event: SettlementEvent) -> AuditFinding:
        """The effect of one event, without the claim (the consumer holds it). Resumable: a finding
        an earlier delivery recorded is returned, never audited twice."""
        recorded = self.finding(event.event_id)
        if recorded is not None:
            PagedIndex(self.rows, f"findings:{event.tenant_id}").append(event.event_id)
            return recorded
        finding = self._audit(event)
        self._record(finding, event.tenant_id)
        return finding

    def finding(self, event_id: str) -> Optional[AuditFinding]:
        raw = self.rows.get(f"finding:{event_id}")
        return AuditFinding.model_validate(raw) if raw else None

    def _audit(self, event: SettlementEvent) -> AuditFinding:
        s = event.settlement
        band, hits = self.rules.evaluate(event)
        base = dict(event_id=event.event_id, settlement_id=s.settlement_id, event_type=event.event_type,
                    risk_band=band, rule_hits=hits)
        if not hits:
            return AuditFinding(verdict="CLEARED", **base)
        if not self._confirmed(event):
            return AuditFinding(verdict="UNCONFIRMED", **base)

        query = " ".join(f"{h.policy_id} {h.rule} {h.detail}" for h in hits)
        policies = self.rag.retrieve(query, {}, tenant_id=event.tenant_id, rerank_top_n=3)
        state = {"tenant_id": event.tenant_id, "settlement": s.model_dump(mode="json"),
                 "rule_hits": [h.model_dump() for h in hits], "policies": policies}
        config = {"llm_service": self.llm, "min_confidence": 0.5, "max_latency": 5.0}
        self.agent.run(state, config, tenant_id=event.tenant_id, idempotency_key=event.event_id)
        response = state.get("response") or {}
        text = response.get("text") if isinstance(response, dict) else str(response)
        return AuditFinding(verdict="FLAGGED", explanation=text,
                            cited_policies=sorted({p["policy_id"] for p in policies}),
                            groundedness=state.get("groundedness"),
                            degraded=bool(response.get("degraded")) if isinstance(response, dict) else False,
                            **base)

    def _confirmed(self, event: SettlementEvent) -> bool:
        """Deterministic confirmation against the system of record."""
        current = self.source.settlement(event.tenant_id, event.settlement.settlement_id)
        if current is None:
            return False
        return (current.get("status") == event.settlement.status
                and Decimal(str(current.get("amount"))) == event.settlement.amount)

    # ---------------------------------------------------------------- queries
    def list_findings(self, anomaly: Optional[bool] = None) -> List[AuditFinding]:
        ids = PagedIndex(self.rows, f"findings:{self.tenant_id}").ids()
        items = [AuditFinding.model_validate(self.rows.get(f"finding:{i}")) for i in ids]
        if anomaly is None:
            return items
        return [f for f in items if (f.verdict == "FLAGGED") == anomaly]
