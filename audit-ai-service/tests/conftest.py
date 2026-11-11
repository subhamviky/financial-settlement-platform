import copy
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from audit_ai_service.services.audit_service import AuditService
from audit_ai_service.services.llm import build_audit_llm
from audit_ai_service.services.policy_rag import PolicyRAGPipeline

SERVICE_ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = SERVICE_ROOT.parent / "contracts" / "events"


def make_event(event_type="settlement.completed", amount="1250.00", payer="ACC-1000",
               status="COMPLETED", reason=None, minutes=0, settlement_id=None):
    return {
        "event_id": str(uuid.uuid4()),
        "event_type": event_type,
        "schema_version": "1",
        "occurred_at": (datetime(2026, 11, 10, 9, 0, tzinfo=timezone.utc) + timedelta(minutes=minutes)).isoformat(),
        "tenant_id": "acme",
        "correlation_id": str(uuid.uuid4()),
        "settlement": {
            "settlement_id": settlement_id or str(uuid.uuid4()),
            "payer_account": payer, "payee_account": "ACC-2000",
            "amount": amount, "currency": "EUR", "status": status, "reason": reason,
        },
    }


class FakeSettlementSource:
    """Stands in for settlement-service: an event feed and the system-of-record lookup."""

    def __init__(self):
        self.feed = []          # list of events, sequence = index + 1
        self.records = {}       # settlement_id -> settlement view (what the real API returns)

    def publish(self, event, record=True):
        self.feed.append(event)
        if record:
            s = event["settlement"]
            self.records[s["settlement_id"]] = {"settlementId": s["settlement_id"], "amount": s["amount"],
                                                "status": s["status"]}
        return event

    def events_after(self, tenant_id, after_sequence):
        items = [{"sequence": i + 1, "event": copy.deepcopy(e)} for i, e in enumerate(self.feed)
                 if i + 1 > after_sequence and e["tenant_id"] == tenant_id]
        return {"events": items, "next_after_sequence": items[-1]["sequence"] if items else after_sequence}

    def settlement(self, tenant_id, settlement_id):
        return self.records.get(settlement_id)


@pytest.fixture
def source():
    return FakeSettlementSource()


@pytest.fixture
def service(source):
    return AuditService(source=source, rag=PolicyRAGPipeline(SERVICE_ROOT / "policies"),
                        llm=build_audit_llm(), tenant_id="acme")
