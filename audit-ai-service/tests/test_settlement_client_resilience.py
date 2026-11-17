"""Design document G-8: audit-ai's reads from settlement-service are retried on transport
failures and 5xx, never on 4xx, and a circuit breaker stops calls during an outage."""
import httpx
import pytest
from e2a_base import CircuitBreaker

from audit_ai_service.services.settlement_client import CircuitOpen, HttpSettlementClient


def client(answers, **kw):
    calls = []

    def handle(request):
        calls.append(request.url.path)
        answer = answers.pop(0) if answers else 200
        if answer == "drop":
            raise httpx.ConnectError("connection reset", request=request)
        return httpx.Response(answer, json={"settlementId": "s-1", "status": "COMPLETED", "amount": 10.0})
    return HttpSettlementClient("http://settlement.test", transport=httpx.MockTransport(handle),
                                base_delay=0.0, **kw), calls


def test_a_transient_failure_is_retried():
    c, calls = client(["drop", 503])
    assert c.settlement("acme", "s-1")["status"] == "COMPLETED"
    assert len(calls) == 3


def test_a_4xx_is_an_answer_not_retried():
    c, calls = client([404])
    assert c.settlement("acme", "s-1") is None and len(calls) == 1
    c, calls = client([403])
    with pytest.raises(httpx.HTTPStatusError):
        c.settlement("acme", "s-1")
    assert len(calls) == 1


def test_retries_are_bounded():
    c, calls = client([503, 503, 503, 503], retries=2)
    with pytest.raises(httpx.HTTPStatusError):
        c.events_after("acme", 0)
    assert len(calls) == 3


def test_an_open_circuit_refuses_without_calling():
    breaker = CircuitBreaker(failure_threshold=2, reset_seconds=60.0)
    c, calls = client(["drop"] * 10, retries=0, breaker=breaker)
    for _ in range(2):
        with pytest.raises(httpx.TransportError):
            c.settlement("acme", "s-1")
    with pytest.raises(CircuitOpen):
        c.settlement("acme", "s-1")
    assert len(calls) == 2
