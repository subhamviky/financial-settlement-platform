"""HTTP exposure for audit findings."""
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Request

from ..models.events import AuditFinding
from ..services.audit_service import AuditService

router = APIRouter(prefix="/audits", tags=["audits"])


def get_service(request: Request) -> AuditService:
    return request.app.state.audit_service


@router.get("", response_model=List[AuditFinding])
def list_audits(anomaly: Optional[bool] = None, service: AuditService = Depends(get_service)):
    return service.list_findings(anomaly)


@router.get("/{settlement_id}", response_model=List[AuditFinding])
def audits_for(settlement_id: str, service: AuditService = Depends(get_service)):
    found = [f for f in service.list_findings() if f.settlement_id == settlement_id]
    if not found:
        raise HTTPException(status_code=404, detail="no audit for this settlement")
    return found


@router.post("/poll")
def poll(service: AuditService = Depends(get_service)) -> dict:
    return {"processed": service.poll_once(), "position": service.position}


@router.post("/consume")
def consume(service: AuditService = Depends(get_service)) -> dict:
    """Settle one batch from the Service Bus queue (the background worker does this continuously)."""
    report = service.consume()
    return {k: v for k, v in report.items() if k != "messages"} | {
        "decisions": [m["decision"] for m in report.get("messages", [])]}


@router.post("/reconcile")
def reconcile(service: AuditService = Depends(get_service)) -> dict:
    """Settle the queue's dead letters: confirmed ones removed, never-applied ones replayed."""
    report = service.reconcile_dead_letters()
    return {k: v for k, v in report.items() if isinstance(v, (str, int, float, list, dict, type(None)))}
