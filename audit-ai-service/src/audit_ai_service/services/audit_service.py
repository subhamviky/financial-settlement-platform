"""Audit pipeline: de-duplicate -> deterministic rules -> confirm against the
system of record -> retrieve policies -> agent explanation -> record.

A finding is FLAGGED only after the settlement is confirmed in settlement-service
(same status and amount): the agent may explain, but deterministic controls
decide and confirm (E2A).
"""
from decimal import Decimal
from typing import Dict, List, Optional

from e2a_base import BaseIdempotencyStore, ClaimStatus
from e2a_inmemory_adapters import InMemoryIdempotencyStore
from e2a_simulated_llm import SimulatedLLMService

from ..agents.settlement_audit_agent import SettlementAuditAgent
from ..models.events import AuditFinding, SettlementEvent
from .policy_rag import PolicyRAGPipeline
from .risk_rules import RiskRules
from .settlement_client import SettlementSource, events_from

AUDITED_TYPES = ("settlement.completed", "settlement.compensated")


class AuditService:
    def __init__(self, source: SettlementSource, rag: PolicyRAGPipeline, llm: SimulatedLLMService,
                 tenant_id: str, rules: RiskRules = None, store: BaseIdempotencyStore = None) -> None:
        self.source, self.rag, self.llm, self.tenant_id = source, rag, llm, tenant_id
        self.rules = rules or RiskRules()
        self.store = store or InMemoryIdempotencyStore()
        self.agent = SettlementAuditAgent(rag)
        self.position = 0
        self.findings: Dict[str, AuditFinding] = {}

    # ------------------------------------------------------------------ feed
    def poll_once(self) -> int:
        feed = self.source.events_after(self.tenant_id, self.position)
        processed = 0
        for raw in events_from(feed):
            if self.process(SettlementEvent.model_validate(raw)) is not None:
                processed += 1
        self.position = max(self.position, int(feed.get("next_after_sequence", self.position)))
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
        self.findings[event.event_id] = finding
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
        items = list(self.findings.values())
        if anomaly is None:
            return items
        return [f for f in items if (f.verdict == "FLAGGED") == anomaly]
