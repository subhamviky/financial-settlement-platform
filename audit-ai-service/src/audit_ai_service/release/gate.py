"""Release gate for audit-ai-service, on E2A's ProjectReleaseGate (E2A v1.2.2, note C-04).

run_pipeline() (inherited, public) runs the seven fixed stages. ProjectReleaseGate supplies
the generic ones (secret scan, pytest, conformance, compiling src/). This class scores the
evaluation set, evals/golden.json: one settlement event per case, run through a fresh audit
service against an in-process stand-in for settlement-service. A case scores 1.0 when the
verdict, the risk band and the cited policies are the expected ones and, for a flag, the
explanation is fully grounded in the retrieved policies and not degraded. Every case must
score 1.0 (CONFIG below).

No red-team set: the service takes no free text from users (dynamic_security NOT_CONFIGURED).
Conformance runs strict: the v1.1 hooks the service still uses raise no R1, R2 or D1 finding.
Usage:  poetry run python -m audit_ai_service.release.gate [--skip-tests] [--report FILE]
"""
import copy
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from e2a.release import ProjectReleaseGate, run_release_gate

from ..models.events import SettlementEvent
from ..services.audit_service import AuditService
from ..services.llm import build_audit_llm
from ..services.policy_rag import PolicyRAGPipeline

ROOT = Path(__file__).resolve().parents[3]


class InProcessSettlementSource:
    """settlement-service's event feed and system-of-record lookup, in memory."""

    def __init__(self):
        self.records = {}

    def record(self, event: dict) -> None:
        s = event["settlement"]
        self.records[s["settlement_id"]] = {"settlementId": s["settlement_id"], "amount": s["amount"],
                                            "status": s["status"]}

    def events_after(self, tenant_id, after_sequence):
        return {"events": [], "next_after_sequence": after_sequence}

    def settlement(self, tenant_id, settlement_id):
        return self.records.get(settlement_id)


def event_of(spec: dict, minutes: int = 0) -> dict:
    return {
        "event_id": str(uuid.uuid4()), "event_type": spec.get("event_type", "settlement.completed"),
        "schema_version": "1", "tenant_id": "acme", "correlation_id": str(uuid.uuid4()),
        "occurred_at": (datetime(2026, 11, 10, 9, 0, tzinfo=timezone.utc) + timedelta(minutes=minutes)).isoformat(),
        "settlement": {"settlement_id": str(uuid.uuid4()), "payer_account": spec.get("payer", "ACC-1000"),
                       "payee_account": "ACC-2000", "amount": spec["amount"], "currency": "EUR",
                       "status": spec.get("status", "COMPLETED"), "reason": spec.get("reason")},
    }


class AuditReleaseGate(ProjectReleaseGate):
    def __init__(self, root: Path = ROOT, run_tests: bool = True) -> None:
        super().__init__(root, run_tests)

    def _score_case(self, case) -> float:
        source = InProcessSettlementSource()
        service = AuditService(source=source, rag=PolicyRAGPipeline(self.root / "policies"),
                               llm=build_audit_llm(), tenant_id="acme")
        finding = None
        for minute in range(int(case.get("repeat", 1))):          # velocity cases: several settlements
            event = event_of(case["event"], minutes=minute)
            if case.get("in_system_of_record", True):
                source.record(event)
            finding = service.process(SettlementEvent.model_validate(copy.deepcopy(event)))
        expected = case["expected"]
        ok = (finding is not None and finding.verdict == expected["verdict"]
              and finding.risk_band == expected["risk_band"]
              and set(expected.get("policies", [])) <= set(finding.cited_policies))
        if ok and finding.verdict == "FLAGGED":
            ok = finding.groundedness == 1.0 and not finding.degraded
        return 1.0 if ok else 0.0


# The audit decisions are deterministic, so every case must be right: threshold 1.0, not
# the 0.85 a model-written answer gets (one wrong case of seven would still score 0.857).
CONFIG = '{"fleet_replica_count": 1, "faithfulness_threshold": 1.0}'


def main(argv=None) -> int:
    return run_release_gate(["--root", str(ROOT), "--config", CONFIG, *(sys.argv[1:] if argv is None else argv)],
                            gate_class=AuditReleaseGate)


if __name__ == "__main__":
    sys.exit(main())
