"""The release gate (E2A ProjectReleaseGate) and its evaluation set."""
import json
import shutil

from audit_ai_service.release.gate import CONFIG, ROOT, AuditReleaseGate


def test_the_release_gate_passes_every_stage_with_every_case_right():
    gate = AuditReleaseGate(run_tests=False)
    report = gate.run_pipeline(json.loads(CONFIG))
    assert report["status"] == "SUCCESS" and report["rag_eval_score"] == 1.0
    assert len(report["stages"]) == 7 and len(gate.evidence["evaluation"]) == 7


def test_a_wrong_decision_fails_the_gate(tmp_path):
    for folder in ("src", "policies", "evals"):
        shutil.copytree(ROOT / folder, tmp_path / folder, ignore=shutil.ignore_patterns("__pycache__"))
    golden = json.loads((tmp_path / "evals" / "golden.json").read_text())
    golden[0]["expected"]["verdict"] = "FLAGGED"            # a 99.00 settlement is not a flag
    (tmp_path / "evals" / "golden.json").write_text(json.dumps(golden))
    report = AuditReleaseGate(tmp_path, run_tests=False).run_pipeline(json.loads(CONFIG))
    assert report["status"] == "FAILED" and "below threshold" in report["error"]
