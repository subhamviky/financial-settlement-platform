"""Pydantic models for the settlement event contract (contracts/events/settlement-event.v1.schema.json)."""
from datetime import datetime
from decimal import Decimal
from typing import List, Literal, Optional

from pydantic import BaseModel, Field

EventType = Literal["ledger.posted", "settlement.completed", "settlement.compensated", "settlement.rejected",
                    "ledger.adjusted"]


class SettlementInfo(BaseModel):
    settlement_id: str
    payer_account: str
    payee_account: str
    amount: Decimal
    currency: str
    status: str
    reason: Optional[str] = None


class SettlementEvent(BaseModel):
    event_id: str
    event_type: EventType
    schema_version: Literal["1"]
    occurred_at: datetime
    tenant_id: str
    correlation_id: str
    settlement: SettlementInfo
    adjustment: Optional[dict] = None          # only on ledger.adjusted (contract v1, additive)


class RuleHit(BaseModel):
    rule: str
    policy_id: str
    detail: str


class AuditFinding(BaseModel):
    event_id: str
    settlement_id: str
    event_type: EventType
    verdict: Literal["CLEARED", "FLAGGED", "UNCONFIRMED"]
    risk_band: str
    rule_hits: List[RuleHit] = Field(default_factory=list)
    explanation: Optional[str] = None
    cited_policies: List[str] = Field(default_factory=list)
    groundedness: Optional[float] = None
    degraded: bool = False
