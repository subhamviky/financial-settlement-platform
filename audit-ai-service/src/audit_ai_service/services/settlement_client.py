"""Governed reads from the settlement system of record (settlement-service)."""
from typing import Any, Dict, List, Optional, Protocol

import httpx


class SettlementSource(Protocol):
    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]: ...

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]: ...


class HttpSettlementClient:
    def __init__(self, base_url: str, timeout: float = 5.0) -> None:
        self._http = httpx.Client(base_url=base_url, timeout=timeout)

    def events_after(self, tenant_id: str, after_sequence: int) -> Dict[str, Any]:
        r = self._http.get("/api/v1/events", params={"afterSequence": after_sequence, "limit": 100},
                           headers={"X-Tenant-Id": tenant_id})
        r.raise_for_status()
        return r.json()

    def settlement(self, tenant_id: str, settlement_id: str) -> Optional[Dict[str, Any]]:
        r = self._http.get(f"/api/v1/settlements/{settlement_id}", headers={"X-Tenant-Id": tenant_id})
        if r.status_code == 404:
            return None
        r.raise_for_status()
        return r.json()


def events_from(feed: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [item["event"] for item in feed.get("events", [])]
