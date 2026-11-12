from audit_ai_service.models.events import SettlementEvent

from .conftest import make_event


def test_small_settlement_is_cleared(service, source):
    source.publish(make_event(amount="99.00"))
    assert service.poll_once() == 1
    [finding] = service.list_findings()
    assert finding.verdict == "CLEARED" and finding.rule_hits == []


def test_large_settlement_is_flagged_and_explained_from_retrieved_policy(service, source):
    event = source.publish(make_event(amount="240000.00"))
    service.poll_once()
    [finding] = service.list_findings(anomaly=True)
    assert finding.verdict == "FLAGGED" and finding.risk_band == "HIGH"
    assert "POL-AML-01" in finding.cited_policies
    assert "POL-AML-01" in finding.explanation and event["settlement"]["settlement_id"] in finding.explanation
    assert finding.groundedness == 1.0 and not finding.degraded


def test_flag_requires_confirmation_from_the_system_of_record(service, source):
    source.publish(make_event(amount="240000.00"), record=False)          # unknown to settlement-service
    mismatch = make_event(amount="240000.00")
    source.publish(mismatch)
    source.records[mismatch["settlement"]["settlement_id"]]["amount"] = "1.00"   # disagrees with the event
    service.poll_once()
    assert {f.verdict for f in service.list_findings()} == {"UNCONFIRMED"}
    assert service.list_findings(anomaly=True) == []


def test_each_event_is_processed_once_even_if_delivered_again(service, source):
    event = make_event(amount="240000.00")
    source.publish(event)
    service.poll_once()
    assert service.process(SettlementEvent.model_validate(event)) is None      # redelivery: de-duplicated
    assert len(service.list_findings()) == 1


def test_only_settlement_outcomes_are_audited_and_the_position_advances(service, source):
    source.publish(make_event("ledger.posted", status="LEDGER_POSTED"))
    source.publish(make_event("settlement.rejected", status="REJECTED", reason="policy"))
    source.publish(make_event())
    assert service.poll_once() == 1
    assert service.position == 3
    assert service.poll_once() == 0


def test_new_and_unknown_event_types_never_stop_the_feed(service, source):
    adjusted = make_event("ledger.adjusted")
    adjusted["adjustment"] = {"adjustment_id": "a1", "type": "WRITE_OFF", "amount": "2.50"}
    source.publish(adjusted)
    future = make_event()
    future["event_type"] = "settlement.disputed"          # a type a later contract version might add
    source.publish(future)
    source.publish(make_event())
    assert service.poll_once() == 1 and service.position == 3


def test_without_a_usable_answer_the_agent_falls_back_to_the_rules(service, source):
    service.llm._rules.clear()                    # the model answers nothing useful
    source.publish(make_event(amount="240000.00"))
    service.poll_once()
    [finding] = service.list_findings(anomaly=True)
    assert finding.degraded and "large-amount" in finding.explanation and finding.groundedness == 0.0
