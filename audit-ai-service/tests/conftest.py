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


@pytest.fixture(autouse=True)
def no_e2a_v11_adapters():
    """v1.3 preparation (design note V13-01): fail a test that uses E2A's v1.1 adapters, which
    v1.3 removes. The warning is recorded, not raised: BaseAgent.run() would swallow an error."""
    import warnings
    with warnings.catch_warnings(record=True) as seen:
        warnings.simplefilter("always")
        yield
    legacy = [str(w.message) for w in seen if "removed in v1.3" in str(w.message)]
    assert not legacy, legacy


# The Cosmos DB emulator's published, well-known key (not a secret).
COSMOS_EMULATOR_KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw=="


@pytest.fixture(autouse=True)
def isolated_cosmos_container(monkeypatch):
    """With AUDIT_STATE_BACKEND=cosmos (CI runs the whole suite a second time on the Cosmos DB
    emulator), each test gets its own container: state is durable, so tests must not share it."""
    import os
    if os.getenv("AUDIT_STATE_BACKEND") != "cosmos":
        yield
        return
    from azure.cosmos import CosmosClient, PartitionKey
    database = CosmosClient(os.environ["COSMOS_ENDPOINT"], os.environ.get("COSMOS_KEY", COSMOS_EMULATOR_KEY)) \
        .create_database_if_not_exists("audit-test")
    container = database.create_container(f"state-{uuid.uuid4().hex[:10]}", PartitionKey("/pk"), default_ttl=-1)
    monkeypatch.setenv("COSMOS_KEY", os.environ.get("COSMOS_KEY", COSMOS_EMULATOR_KEY))
    monkeypatch.setenv("COSMOS_DATABASE", "audit-test")
    monkeypatch.setenv("COSMOS_CONTAINER", container.id)
    yield
    database.delete_container(container.id)
