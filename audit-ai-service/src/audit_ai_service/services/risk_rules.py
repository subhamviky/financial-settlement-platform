"""Deterministic risk rules. They decide WHETHER a settlement is flagged; the
agent only explains a decision these rules already made."""
from collections import defaultdict, deque
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Deque, Dict, List, Tuple

from capability_oriented_dsa import DSAFacade, SequenceState

from ..models.events import RuleHit, SettlementEvent

# (upper limit inclusive, band). Must stay sorted by limit: the governed
# LOWER_BOUND call checks that precondition instead of answering wrongly.
RISK_BANDS: List[Tuple[Decimal, str]] = [
    (Decimal("10000.00"), "LOW"),
    (Decimal("100000.00"), "MEDIUM"),
    (Decimal("250000.00"), "HIGH"),
]
ABOVE_ALL_BANDS = "CRITICAL"


def risk_band(amount: Decimal) -> str:
    """The first band whose upper limit is >= amount (DSA governed LOWER_BOUND)."""
    result = DSAFacade().execute(
        structure_type="SEQUENCE", capability="SEARCH", operation="LOWER_BOUND",
        state=SequenceState(RISK_BANDS), target=amount, key=lambda row: row[0])
    index = result.data
    return RISK_BANDS[index][1] if index < len(RISK_BANDS) else ABOVE_ALL_BANDS


class VelocityWindow:
    """Settlements per payer inside a sliding time window."""

    def __init__(self, window: timedelta = timedelta(hours=1), max_count: int = 5) -> None:
        self.window, self.max_count = window, max_count
        self._seen: Dict[str, Deque[datetime]] = defaultdict(deque)

    def add(self, payer: str, at: datetime) -> int:
        times = self._seen[payer]
        times.append(at)
        while times and at - times[0] > self.window:
            times.popleft()
        return len(times)


class RiskRules:
    def __init__(self, velocity: VelocityWindow = None) -> None:
        self.velocity = velocity or VelocityWindow()

    def evaluate(self, event: SettlementEvent) -> Tuple[str, List[RuleHit]]:
        s = event.settlement
        band = risk_band(s.amount)
        hits: List[RuleHit] = []
        if band in ("HIGH", ABOVE_ALL_BANDS):
            hits.append(RuleHit(rule="large-amount", policy_id="POL-AML-01",
                                detail=f"amount {s.amount} {s.currency} is in the {band} band"))
        if event.event_type == "settlement.completed":
            count = self.velocity.add(s.payer_account, event.occurred_at)
            if count > self.velocity.max_count:
                hits.append(RuleHit(rule="velocity", policy_id="POL-AML-02",
                                    detail=f"{count} settlements from {s.payer_account} within the window"))
        if event.event_type == "settlement.compensated":
            hits.append(RuleHit(rule="clearing-decline", policy_id="POL-OPS-03",
                                detail=f"compensated: {s.reason or 'no reason given'}"))
        return band, hits
