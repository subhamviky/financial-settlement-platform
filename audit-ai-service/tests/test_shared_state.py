"""Several Container App replicas on one Cosmos DB container (e2a-adapters, design note D-03).
The Container App runs up to three replicas; before this, each kept its own claims, findings and
feed position in memory. Needs the Cosmos DB emulator; skipped otherwise."""
import os
import threading

import pytest

pytestmark = pytest.mark.skipif(os.getenv("AUDIT_STATE_BACKEND") != "cosmos" or not os.getenv("COSMOS_ENDPOINT"),
                                reason="AUDIT_STATE_BACKEND=cosmos and COSMOS_ENDPOINT not set (no Cosmos DB emulator)")

from audit_ai_service.services.audit_service import AuditService
from audit_ai_service.services.llm import build_audit_llm
from audit_ai_service.services.policy_rag import PolicyRAGPipeline
from .conftest import SERVICE_ROOT, make_event


def replica(source):
    return AuditService(source=source, rag=PolicyRAGPipeline(SERVICE_ROOT / "policies"),
                        llm=build_audit_llm(), tenant_id="acme")


def test_an_event_is_audited_once_across_replicas(source):
    a, b = replica(source), replica(source)
    event = make_event(amount="250000.00")                      # large enough to be flagged
    source.publish(event)
    audited = []
    original = a.agent.run, b.agent.run

    def counting(run):
        def wrapper(*args, **kwargs):
            audited.append(1)
            return run(*args, **kwargs)
        return wrapper
    a.agent.run, b.agent.run = counting(original[0]), counting(original[1])
    threads = [threading.Thread(target=r.poll_once) for r in (a, b)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    [finding] = a.list_findings()
    assert finding.verdict == "FLAGGED", "the agent runs only for a flagged event: this test needs one"
    assert len(audited) == 1, "the claim is shared: one replica audits, the other skips"
    assert [f.event_id for f in b.list_findings()] == [event["event_id"]]


def test_the_feed_position_is_shared_and_never_moves_back(source):
    a, b = replica(source), replica(source)
    for _ in range(3):
        source.publish(make_event())
    assert a.poll_once() == 3 and b.position == 3
    assert b.poll_once() == 0, "b starts where a stopped"
    b._advance(1)
    assert a.position == 3


def test_a_restarted_replica_keeps_the_findings(source):
    source.publish(make_event())
    replica(source).poll_once()
    fresh = replica(source)                                      # a new revision or a restart
    assert len(fresh.list_findings()) == 1 and fresh.position == 1
