from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest
from capability_oriented_dsa import AlgorithmPreconditionError, DSAFacade, SequenceState

from audit_ai_service.models.events import SettlementEvent
from audit_ai_service.services.risk_rules import RiskRules, VelocityWindow, risk_band

from .conftest import make_event


@pytest.mark.parametrize("amount, band", [("0.01", "LOW"), ("10000.00", "LOW"), ("10000.01", "MEDIUM"),
                                          ("250000.00", "HIGH"), ("250000.01", "CRITICAL")])
def test_risk_band_uses_inclusive_upper_limits(amount, band):
    assert risk_band(Decimal(amount)) == band


def test_governed_lower_bound_refuses_unsorted_bands():
    with pytest.raises(AlgorithmPreconditionError):
        DSAFacade().execute(structure_type="SEQUENCE", capability="SEARCH", operation="LOWER_BOUND",
                            state=SequenceState([(3, "b"), (1, "a")]), target=2, key=lambda r: r[0])


def test_velocity_flags_the_sixth_settlement_within_an_hour():
    rules = RiskRules(VelocityWindow(timedelta(hours=1), max_count=5))
    hits = [rules.evaluate(SettlementEvent.model_validate(make_event(minutes=m)))[1] for m in range(6)]
    assert all(not h for h in hits[:5])
    assert [h.rule for h in hits[5]] == ["velocity"]


def test_velocity_window_forgets_old_settlements():
    window = VelocityWindow(timedelta(hours=1), max_count=5)
    start = datetime(2026, 11, 10, tzinfo=timezone.utc)
    for m in range(5):
        window.add("ACC-1", start + timedelta(minutes=m))
    assert window.add("ACC-1", start + timedelta(hours=2)) == 1


def test_compensated_settlements_hit_the_clearing_rule():
    event = SettlementEvent.model_validate(make_event("settlement.compensated", status="COMPENSATED",
                                                      reason="declined by clearing"))
    band, hits = RiskRules().evaluate(event)
    assert band == "LOW" and [h.policy_id for h in hits] == ["POL-OPS-03"]
