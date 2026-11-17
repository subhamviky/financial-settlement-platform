"""Governed reads from the settlement system of record (settlement-service).

Both calls are reads, so they are safe to repeat: a transport failure or a 5xx is retried with
jittered backoff, bounded by a retry budget, behind a circuit breaker (E2A's e2a_base). A 4xx is
an answer, never retried. When the breaker is open the call fails at once (CircuitOpen), so a
settlement-service outage does not hold every audit for its full timeout; the queue redelivers
the event and the feed poll tries again later. (Design document G-8.)
"""
import random
import time
from typing import Any, Callable, Dict, List, Optional, Protocol, TypeVar

import httpx
from e2a_base import CircuitBreaker, RetryBudget

T = TypeVar("T")


class CircuitOpen(RuntimeError):
    """settlement-service failed repeatedly: calls are refused for a while without trying."""


class SettlementSource(Protocol):
    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]: ...

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]: ...


class HttpSettlementClient:
    """`credentials`: an E2A BaseCredentialProvider (e2a_adapters.workload) that adds audit-ai's own
    token to each call (hub design note SI-01); None sends the tenant header only, as before."""

    def __init__(self, base_url: str, timeout: float = 5.0, credentials=None, transport=None,
                 retries: int = 2, base_delay: float = 0.05, breaker: Optional[CircuitBreaker] = None) -> None:
        self._base = base_url.rstrip("/")
        self._http = httpx.Client(base_url=base_url, timeout=timeout, transport=transport)
        self._credentials = credentials
        self._retries, self._base_delay = retries, base_delay
        self._breaker = breaker or CircuitBreaker(failure_threshold=5, reset_seconds=30.0)
        self._budget = RetryBudget()

    def _resilient(self, call: Callable[[], httpx.Response]) -> httpx.Response:
        """A read: retried on a transport failure or a 5xx; a 4xx is returned as the answer."""
        self._budget.deposit()
        attempt = 0
        while True:
            if not self._breaker.allow():
                raise CircuitOpen("settlement-service circuit open")
            try:
                response = call()
                if response.status_code >= 500:
                    raise httpx.HTTPStatusError(f"settlement-service answered {response.status_code}",
                                                request=response.request, response=response)
                self._breaker.record_success()
                return response
            except (httpx.TransportError, httpx.HTTPStatusError):
                self._breaker.record_failure()
                attempt += 1
                if attempt > self._retries or not self._budget.withdraw():
                    raise
                time.sleep(self._base_delay * (2 ** (attempt - 1)) * (0.5 + random.random()))

    def _headers(self, tenant_id: str, path: str) -> Dict[str, str]:
        headers = {"X-Tenant-Id": tenant_id}
        if self._credentials is not None:
            headers.update(self._credentials.headers(self._base + path))
        return headers

    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]:
        r = self._resilient(lambda: self._http.get("/api/v1/events", params={"afterSequence": after_sequence, "limit": 100},
                                                   headers=self._headers(tenant_id, "/api/v1/events")))
        r.raise_for_status()
        return r.json()

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]:
        path = f"/api/v1/settlements/{settlement_id}"
        r = self._resilient(lambda: self._http.get(path, headers=self._headers(tenant_id, path)))
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.json()


def events_from(feed: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [item["event"] for item in feed.get("events", [])]
