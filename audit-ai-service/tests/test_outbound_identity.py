"""Hub design note SI-01: audit-ai's calls to settlement-service carry its own workload token
when one is configured, and only the tenant header otherwise."""
import httpx

from audit_ai_service.services.settlement_client import HttpSettlementClient


class Credentials:
    """Stands for e2a_adapters.workload's provider (Azure managed identity in the cloud)."""

    def __init__(self):
        self.targets = []

    def headers(self, target, scope=None, config=None, **kwargs):
        self.targets.append(target)
        return {"Authorization": "Bearer mi-token"}


def recording_transport(seen):
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(dict(request.headers))
        if request.url.path.endswith("/events"):
            return httpx.Response(200, json={"events": [], "next_after_sequence": 0})
        return httpx.Response(200, json={"settlementId": "s-1", "status": "COMPLETED"})
    return httpx.MockTransport(handle)


def test_calls_carry_the_workload_token_for_their_target():
    seen, creds = [], Credentials()
    client = HttpSettlementClient("https://settlement.example", credentials=creds, transport=recording_transport(seen))
    client.events_after("acme", 0)
    client.settlement("acme", "s-1")
    assert all(h["authorization"] == "Bearer mi-token" and h["x-tenant-id"] == "acme" for h in seen)
    assert creds.targets == ["https://settlement.example/api/v1/events", "https://settlement.example/api/v1/settlements/s-1"]


def test_without_credentials_only_the_tenant_header_is_sent():
    seen = []
    HttpSettlementClient("https://settlement.example", transport=recording_transport(seen)).settlement("acme", "s-1")
    assert "authorization" not in seen[0] and seen[0]["x-tenant-id"] == "acme"
