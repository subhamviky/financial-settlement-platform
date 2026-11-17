"""Governed reads from the settlement system of record (settlement-service)."""
from typing import Any, Dict, List, Optional, Protocol

import httpx


class SettlementSource(Protocol):
    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]: ...

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]: ...


class HttpSettlementClient:
    """`credentials`: an E2A BaseCredentialProvider (e2a_adapters.workload) that adds audit-ai's own
    token to each call (hub design note SI-01); None sends the tenant header only, as before."""

    def __init__(self, base_url: str, timeout: float = 5.0, credentials=None, transport=None) -> None:
        self._base = base_url.rstrip("/")
        self._http = httpx.Client(base_url=base_url, timeout=timeout, transport=transport)
        self._credentials = credentials

    def _headers(self, tenant_id: str, path: str) -> Dict[str, str]:
        headers = {"X-Tenant-Id": tenant_id}
        if self._credentials is not None:
            headers.update(self._credentials.headers(self._base + path))
        return headers

    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]:
        r = self._http.get("/api/v1/events", params={"afterSequence": after_sequence, "limit": 100},
                           headers=self._headers(tenant_id, "/api/v1/events"))
        r.raise_for_status()
        return r.json()

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]:
        path = f"/api/v1/settlements/{settlement_id}"
        r = self._http.get(path, headers=self._headers(tenant_id, path))
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.json()


def events_from(feed: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [item["event"] for item in feed.get("events", [])]
