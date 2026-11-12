"""Models of the audit service: the settlement event (generated from the contract) and the findings."""
from datetime import datetime
from decimal import Decimal
from typing import List, Literal, Optional

from pydantic import BaseModel, Field

# The event contract (contracts/events/settlement-event.v1.schema.json) is generated into
# models by A2C (a2c_sdlc.contracts); the hand-typed copies are gone.
from ..contracts.settlement_event import Adjustment, Settlement as SettlementInfo, SettlementEvent  # noqa: E402,F401

EventType = Literal["ledger.posted", "settlement.completed", "settlement.compensated", "settlement.rejected",
                    "ledger.adjusted"]


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
