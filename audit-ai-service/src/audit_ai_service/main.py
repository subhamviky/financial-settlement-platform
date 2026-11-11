"""audit-ai-service - FastAPI entry point (scaffolded by P0, extended by hand)."""
import logging
import os
from pathlib import Path

from fastapi import FastAPI

from .routers.audits import router as audits_router
from .services.audit_service import AuditService
from .services.llm import build_audit_llm
from .services.policy_rag import PolicyRAGPipeline
from .services.settlement_client import HttpSettlementClient

logger = logging.getLogger("audit_ai_service")

POLICY_DIR = Path(os.getenv("POLICY_DIR", Path(__file__).resolve().parents[2] / "policies"))

app = FastAPI(title="audit-ai-service")
app.state.audit_service = AuditService(
    source=HttpSettlementClient(os.getenv("SETTLEMENT_API_URL", "http://localhost:8080")),
    rag=PolicyRAGPipeline(POLICY_DIR),
    llm=build_audit_llm(),
    tenant_id=os.getenv("AUDIT_TENANT", "acme"),
)
app.include_router(audits_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe used by the container HEALTHCHECK and the platform."""
    return {"status": "UP", "service": "audit-ai-service"}
