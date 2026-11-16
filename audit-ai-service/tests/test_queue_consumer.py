"""Settlement events from the Service Bus queue, settled by E2A's IdempotentConsumer: in memory,
then on the Service Bus emulator (SERVICEBUS_CONNECTION_STRING; skipped otherwise)."""
import json
import os
import uuid

import pytest

from e2a import InMemoryBroker
from e2a_inmemory_adapters import InMemoryLease

from audit_ai_service.services.audit_service import AuditService
from audit_ai_service.services.llm import build_audit_llm
from audit_ai_service.services.policy_rag import PolicyRAGPipeline
from .conftest import SERVICE_ROOT, make_event

QUEUE = "settlement-events"
LARGE = dict(amount="250000.00")                          # flagged by the rules


def as_sent(broker, event, queue=QUEUE, **attributes):
    """Publish as settlement-service's outbox relay does: the event JSON with its tenant, its id as the
    idempotency key and its type as attributes."""
    attrs = {"tenant_id": event["tenant_id"], "idempotency_key": event["event_id"],
             "event_type": event["event_type"], **attributes}
    broker.publish(queue, event, attrs)


@pytest.fixture
def broker():
    return InMemoryBroker()


@pytest.fixture
def queued(source, broker):
    return AuditService(source=source, rag=PolicyRAGPipeline(SERVICE_ROOT / "policies"), llm=build_audit_llm(),
                        tenant_id="acme", broker=broker, queue=QUEUE, lease=InMemoryLease())


def decisions(report):
    return [m["decision"] for m in report["messages"]]


def test_an_event_on_the_queue_is_audited_and_acknowledged(queued, source, broker):
    event = source.publish(make_event(**LARGE))
    as_sent(broker, event)
    report = queued.consume()
    assert decisions(report) == ["processed"] and broker.receive(QUEUE) == []
    assert queued.finding(event["event_id"]).verdict == "FLAGGED"
    assert [f.event_id for f in queued.list_findings()] == [event["event_id"]]


def test_an_event_seen_on_the_queue_and_the_feed_is_audited_once(queued, source, broker):
    first, second = source.publish(make_event()), source.publish(make_event(minutes=1))
    as_sent(broker, first)
    as_sent(broker, first)                                   # a redelivery
    assert decisions(queued.consume()) == ["processed", "duplicate"]
    assert queued.poll_once() == 1                           # the feed: only the second is new
    as_sent(broker, second)
    assert decisions(queued.consume()) == ["duplicate"]      # the queue copy of an event the feed audited
    assert sorted(f.event_id for f in queued.list_findings()) == sorted([first["event_id"], second["event_id"]])


def test_an_event_type_this_service_does_not_audit_is_acknowledged_unread(queued, source, broker):
    as_sent(broker, make_event(event_type="ledger.posted"))
    as_sent(broker, {**make_event(), "event_type": "settlement.future_type"})
    assert decisions(queued.consume()) == ["processed", "processed"] and queued.list_findings() == []


def test_a_poison_event_is_dead_lettered_with_the_reason(queued, broker):
    broken = make_event()
    del broken["settlement"]                                 # breaks the contract
    as_sent(broker, broken)
    as_sent(broker, make_event(), tenant_id="globex")        # the attribute disagrees with the envelope
    assert decisions(queued.consume()) == ["dead_lettered", "dead_lettered"]
    dead = broker.receive_dead_letters(QUEUE, 10)
    assert len(dead) == 2 and queued.list_findings() == []


def test_dead_letters_are_confirmed_before_they_are_replayed(queued, source, broker):
    audited = source.publish(make_event(**LARGE))
    queued.process_raw(audited)                              # took effect before it was dead-lettered
    never = source.publish(make_event(minutes=1))
    for event in (audited, never):
        as_sent(broker, event)
        message = broker.receive(QUEUE)[0]
        broker.dead_letter(QUEUE, message, "lock lost after delivery count exceeded")
    report = queued.reconcile_dead_letters()
    assert report["status"] == "RECONCILED" and broker.receive_dead_letters(QUEUE, 10) == []
    assert queued.finding(never["event_id"]) is not None     # replayed and audited
    assert sorted(f.event_id for f in queued.list_findings()) == sorted([audited["event_id"], never["event_id"]])


def test_without_a_broker_the_queue_paths_are_disabled(service):
    assert service.consume()["status"] == "DISABLED" and service.reconcile_dead_letters()["status"] == "DISABLED"


# ---------------------------------------------------------------- on the emulator
@pytest.mark.skipif(not os.getenv("SERVICEBUS_CONNECTION_STRING"), reason="SERVICEBUS_CONNECTION_STRING not set")
def test_events_sent_as_settlement_service_sends_them_are_audited_once_on_service_bus(source):
    from azure.servicebus import ServiceBusClient, ServiceBusMessage
    from audit_ai_service.services.state import event_ports
    queue = os.getenv("AUDIT_TEST_QUEUE", "audit-test-events")       # not the worker's queue
    broker, _, lease = event_ports({"SERVICEBUS_CONNECTION_STRING": os.environ["SERVICEBUS_CONNECTION_STRING"],
                                    "SERVICEBUS_QUEUE": queue})
    service = AuditService(source=source, rag=PolicyRAGPipeline(SERVICE_ROOT / "policies"), llm=build_audit_llm(),
                           tenant_id="acme", broker=broker, queue=queue, lease=lease)
    while service.consume(wait_seconds=1)["messages"]:      # start from an empty queue
        pass
    for m in broker.receive_dead_letters(queue, 50):
        broker.ack_dead_letter(queue, m)

    good = source.publish(make_event(**LARGE))
    poison = {**make_event(), "settlement": None}
    with ServiceBusClient.from_connection_string(os.environ["SERVICEBUS_CONNECTION_STRING"]) as client, \
            client.get_queue_sender(queue_name=queue) as sender:
        for event in (good, good, poison):                     # exactly what the Java publisher builds
            sender.send_messages(ServiceBusMessage(
                json.dumps(event), message_id=event["event_id"], content_type="application/json",
                application_properties={"tenant_id": event["tenant_id"], "idempotency_key": event["event_id"],
                                        "event_type": event["event_type"]}))
    seen = []
    for _ in range(10):
        seen += [m["decision"] for m in service.consume(wait_seconds=2)["messages"]]
        if len(seen) >= 3:
            break
    assert sorted(seen) == ["dead_lettered", "duplicate", "processed"], seen
    assert service.finding(good["event_id"]).verdict == "FLAGGED"
    dead = broker.receive_dead_letters(queue, 10)
    assert [m.body["event_id"] for m in dead] == [poison["event_id"]]
    assert "settlement" in dead[0].attributes.get("dead_letter_reason", "") + str(dead[0].attributes)
    for m in dead:
        broker.ack_dead_letter(queue, m)
    broker.close()
