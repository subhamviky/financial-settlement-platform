"""Identity at ingress (E2A v1.7.4): with Container Apps authentication, the API serves only callers
whose verified Entra ID claims resolve to this deployment's tenant."""
import base64
import json

import pytest
from fastapi.testclient import TestClient

DIRECTORY = "9f1c0000-0000-0000-0000-00000000acme"


def principal(directory, *roles):
    """X-MS-CLIENT-PRINCIPAL as Container Apps injects it: base64 JSON, mapped claim names."""
    claims = [{"typ": "http://schemas.microsoft.com/identity/claims/tenantid", "val": directory},
              {"typ": "http://schemas.microsoft.com/identity/claims/objectidentifier", "val": "oid-1"}]
    claims += [{"typ": "roles", "val": r} for r in roles]
    body = {"auth_typ": "aad", "name_typ": "name", "role_typ": "roles", "claims": claims}
    return {"X-MS-CLIENT-PRINCIPAL": base64.b64encode(json.dumps(body).encode()).decode()}


@pytest.fixture
def client(service, monkeypatch):
    monkeypatch.setenv("E2A_IDENTITY_SOURCE", "azure_container_apps")
    monkeypatch.setenv("E2A_IDENTITY_TENANT_MAP", json.dumps({DIRECTORY: "acme"}))
    from audit_ai_service.main import app
    original = app.state.audit_service
    app.state.audit_service = service
    yield TestClient(app)
    app.state.audit_service = original


def test_a_caller_from_the_tenants_directory_is_served(client):
    assert client.get("/audits", headers=principal(DIRECTORY, "auditor")).status_code == 200


def test_no_principal_or_another_directory_is_refused(client):
    assert client.get("/audits").status_code == 401
    assert client.get("/audits", headers={"X-Tenant-Id": "acme"}).status_code == 401      # headers are not identity
    assert client.get("/audits", headers=principal("other-directory", "auditor")).status_code == 403


def test_a_required_app_role_is_enforced(client, monkeypatch):
    monkeypatch.setenv("AUDIT_REQUIRED_ROLE", "auditor")
    assert client.post("/audits/poll", headers=principal(DIRECTORY, "reader")).status_code == 403
    assert client.post("/audits/poll", headers=principal(DIRECTORY, "reader", "auditor")).status_code == 200


def test_health_stays_open(client):
    assert client.get("/health").status_code == 200
