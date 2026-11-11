from fastapi.testclient import TestClient

from audit_ai_service.main import app


def test_health_reports_up() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
