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
from typing import List, Optional

from e2a import BaseIdempotencyStore, BaseTransactionalStore, ClaimStatus
from e2a_simulated_llm import SimulatedLLMService

from ..agents.settlement_audit_agent import SettlementAuditAgent
from ..models.events import AuditFinding, SettlementEvent
from .policy_rag import PolicyRAGPipeline
from .risk_rules import RiskRules
from .settlement_client import SettlementSource, events_from
from .state import state_ports

AUDITED_TYPES = ("settlement.completed", "settlement.compensated")


class AuditService:
    def __init__(self, source: SettlementSource, rag: PolicyRAGPipeline, llm: SimulatedLLMService,
                 tenant_id: str, rules: RiskRules = None, store: BaseIdempotencyStore = None,
                 rows: BaseTransactionalStore = None) -> None:
        self.source, self.rag, self.llm, self.tenant_id = source, rag, llm, tenant_id
        self.rules = rules or RiskRules()
        if store is None or rows is None:
            default_rows, default_store = state_ports()
            store, rows = store or default_store, rows or default_rows
        self.store, self.rows = store, rows
        self.agent = SettlementAuditAgent(rag)

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
        """Store the finding once and list it in the tenant's index (compare-and-set)."""
        self.rows.put_if_version(f"finding:{finding.event_id}", finding.model_dump(mode="json"), None)
        key = f"finding-index:{tenant_id}"
        while True:
            value, version = self.rows.get_versioned(key) or ({"ids": []}, None)
            if finding.event_id in value["ids"]:
                return
            if self.rows.put_if_version(key, {"ids": value["ids"] + [finding.event_id]}, version).committed:
                return

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
            if self.process(SettlementEvent.model_validate(raw)) is not None:
                processed += 1
        self._advance(int(feed.get("next_after_sequence", start)))
        return processed

    # --------------------------------------------------------------- one event
    def process(self, event: SettlementEvent) -> Optional[AuditFinding]:
        if event.event_type not in AUDITED_TYPES:
            return None
        claim = self.store.claim(event.event_id, tenant_id=event.tenant_id)
        if claim.status != ClaimStatus.CLAIMED:          # REPLAY / IN_PROGRESS: already handled
            return None
        try:
            finding = self._audit(event)
        except Exception:
            self.store.fail(event.event_id, retryable=True, tenant_id=event.tenant_id, owner=claim.owner)
            raise
        self._record(finding, event.tenant_id)
        self.store.complete(event.event_id, finding.model_dump(mode="json"), tenant_id=event.tenant_id,
                            owner=claim.owner)
        return finding

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
        ids = (self.rows.get(f"finding-index:{self.tenant_id}") or {"ids": []})["ids"]
        items = [AuditFinding.model_validate(self.rows.get(f"finding:{i}")) for i in ids]
        if anomaly is None:
            return items
        return [f for f in items if (f.verdict == "FLAGGED") == anomaly]
