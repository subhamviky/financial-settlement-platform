"""GCP-01, E2A v1.11.0: on Cloud Run, IAM verifies the caller's Google ID token and the API reads
it from the headers; the deployment grants each caller (email) this tenant and its roles. The
explaining model follows E2A_PROVIDER_PROFILE."""
import base64
import json
import sys
import types

import pytest
from fastapi.testclient import TestClient

from audit_ai_service.services.llm import build_audit_llm

AUDITOR, ASSISTANT = "auditor@acme.example", "genai-app@demo.iam.gserviceaccount.com"


def b64(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


def id_token(email, iss="https://accounts.google.com"):
    return {"Authorization": f"Bearer {b64({'alg': 'RS256'})}.{b64({'iss': iss, 'email': email})}.c2ln"}


@pytest.fixture
def client(service, monkeypatch):
    monkeypatch.setenv("E2A_IDENTITY_SOURCE", "gcp_cloud_run")
    monkeypatch.setenv("E2A_IDENTITY_TRUSTED_ISSUERS", '["https://accounts.google.com"]')
    monkeypatch.setenv("E2A_IDENTITY_SUBJECT_ROLES", json.dumps({AUDITOR: ["tenant:acme", "auditor"],
                                                                 ASSISTANT: ["tenant:acme"]}))
    from audit_ai_service.main import app
    original = app.state.audit_service
    app.state.audit_service = service
    yield TestClient(app)
    app.state.audit_service = original


def test_granted_callers_are_served(client):
    assert client.get("/audits", headers=id_token(AUDITOR)).status_code == 200
    assert client.get("/audits", headers=id_token(ASSISTANT)).status_code == 200


def test_nobody_else_passes(client):
    assert client.get("/audits").status_code == 401
    assert client.get("/audits", headers={"X-Tenant-Id": "acme"}).status_code == 401
    assert client.get("/audits", headers=id_token("stranger@else.example")).status_code == 403
    assert client.get("/audits", headers=id_token(AUDITOR, iss="https://evil.example")).status_code == 403


def test_a_required_role_still_applies(client, monkeypatch):
    monkeypatch.setenv("AUDIT_REQUIRED_ROLE", "auditor")
    assert client.get("/audits", headers=id_token(AUDITOR)).status_code == 200
    assert client.get("/audits", headers=id_token(ASSISTANT)).status_code == 403


def test_the_model_follows_the_provider_profile(monkeypatch):
    monkeypatch.delenv("E2A_PROVIDER_PROFILE", raising=False)
    assert type(build_audit_llm()).__name__ == "SimulatedLLMService"
    fake = types.ModuleType("e2a_managed")
    fake.bindings_from_profile = lambda profile: types.SimpleNamespace(llm_service=f"model of {profile}")
    monkeypatch.setitem(sys.modules, "e2a_managed", fake)
    monkeypatch.setenv("E2A_PROVIDER_PROFILE", "gemini_api")
    assert build_audit_llm() == "model of gemini_api"
