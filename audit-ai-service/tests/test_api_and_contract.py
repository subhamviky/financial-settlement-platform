import json

import jsonschema
from fastapi.testclient import TestClient

from audit_ai_service.main import app

from .conftest import CONTRACTS, make_event


def test_audit_endpoints(service, source):
    app.state.audit_service = service
    client = TestClient(app)
    small, large = make_event(amount="5.00"), make_event(amount="240000.00")
    source.publish(small)
    source.publish(large)
    assert client.post("/audits/poll").json() == {"processed": 2, "position": 2}
    assert len(client.get("/audits").json()) == 2
    flagged = client.get("/audits", params={"anomaly": "true"}).json()
    assert [f["settlement_id"] for f in flagged] == [large["settlement"]["settlement_id"]]
    assert client.get(f"/audits/{small['settlement']['settlement_id']}").json()[0]["verdict"] == "CLEARED"
    assert client.get("/audits/unknown").status_code == 404


def test_the_events_the_tests_use_satisfy_the_contract():
    schema = json.loads((CONTRACTS / "settlement-event.v1.schema.json").read_text())
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    for event in [make_event(), make_event("settlement.compensated", status="COMPENSATED", reason="x"),
                  json.loads((CONTRACTS / "examples" / "settlement-completed.json").read_text())]:
        validator.validate(event)
