"""Google Cloud (hub design note GCP-01): settlement events pushed by Pub/Sub are audited once,
under the same E2A claim as every other path (PubSubPushDelivery, E2A v1.10.0)."""
import base64
import json

import pytest
from fastapi.testclient import TestClient

from .conftest import make_event


class _Future:
    def result(self, timeout=None):
        return "id"


class FakePublisher:
    def __init__(self):
        self.published = []

    def publish(self, topic, data, **attributes):
        self.published.append((topic, json.loads(data), attributes))
        return _Future()


def push(event, mid="m1", attempt=1, raw=None):
    data = raw if raw is not None else base64.b64encode(json.dumps(event).encode()).decode()
    return {"message": {"data": data, "messageId": mid, "attributes": {"tenant_id": "acme"}},
            "subscription": "projects/demo/subscriptions/audit-settlement-events", "deliveryAttempt": attempt}


@pytest.fixture
def pushed(service, source):
    from e2a_adapters.gcp import PubSubBroker
    publisher = FakePublisher()
    service.broker, service.queue = PubSubBroker("demo", publisher=publisher), "audit-settlement-events"
    return service, source, publisher


def test_a_pushed_event_is_audited_once_and_a_redelivery_is_a_duplicate(pushed):
    service, source, _ = pushed
    event = source.publish(make_event())
    status, report = service.consume_push(push(event))
    assert status == 204 and report["messages"][0]["decision"] == "processed"
    assert service.finding(event["event_id"]) is not None
    status, report = service.consume_push(push(event, mid="m2", attempt=2))
    assert status == 204 and report["messages"][0]["decision"] == "duplicate"
    assert len([f for f in service.list_findings() if f.event_id == event["event_id"]]) == 1


def test_an_event_the_service_does_not_audit_is_acknowledged_unread(pushed):
    service, _, _ = pushed
    status, report = service.consume_push(push(make_event(event_type="settlement.requested")))
    assert status == 204 and report["messages"][0]["decision"] == "processed"


def test_a_malformed_event_is_dead_lettered_with_its_reason(pushed):
    service, _, publisher = pushed
    bad = make_event()
    bad["settlement"]["amount"] = "not money"
    status, report = service.consume_push(push(bad))
    assert status == 204 and report["messages"][0]["decision"] == "dead_lettered"
    topic, body, attrs = publisher.published[-1]
    assert topic == "projects/demo/topics/audit-settlement-events-dlq" and attrs["dead_letter_reason"]


def test_the_http_endpoint_answers_pubsub(pushed):
    from audit_ai_service.main import app
    service, source, _ = pushed
    original = app.state.audit_service
    app.state.audit_service = service
    try:
        client = TestClient(app)
        assert client.post("/pubsub/settlement-events", json=push(source.publish(make_event()))).status_code == 204
        assert client.post("/pubsub/settlement-events", json={"event_id": "x"}).status_code == 400
    finally:
        app.state.audit_service = original


def test_without_pubsub_the_endpoint_says_so(service):
    from audit_ai_service.main import app
    original = app.state.audit_service
    app.state.audit_service = service
    try:
        assert TestClient(app).post("/pubsub/settlement-events", json={}).status_code == 503
    finally:
        app.state.audit_service = original


def test_state_and_broker_from_the_environment():
    from audit_ai_service.services.state import event_ports
    broker, queue, _ = event_ports({"GOOGLE_CLOUD_PROJECT": "demo", "AUDIT_PUBSUB_SUBSCRIPTION": "audit-settlement-events"})
    assert type(broker).__name__ == "PubSubBroker" and queue == "audit-settlement-events"
    assert event_ports({})[0] is None
