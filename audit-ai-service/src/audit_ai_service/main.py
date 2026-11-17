"""audit-ai-service - FastAPI entry point (scaffolded by P0, extended by hand).

When a Service Bus is configured (services/state.py), a background worker receives settlement
events from the queue continuously and settles each with E2A's IdempotentConsumer. Without one,
the service audits through the event feed (POST /audits/poll) only.
"""
import logging
import os
import threading
from contextlib import asynccontextmanager
from pathlib import Path

from e2a_adapters.workload import workload_credentials
from fastapi import FastAPI

from .routers.audits import router as audits_router
from .services.audit_service import AuditService
from .services.llm import build_audit_llm
from .services.policy_rag import PolicyRAGPipeline
from .services.settlement_client import HttpSettlementClient
from .services.state import event_ports

logger = logging.getLogger("audit_ai_service")

POLICY_DIR = Path(os.getenv("POLICY_DIR", Path(__file__).resolve().parents[2] / "policies"))



def consume_forever(service: AuditService, stop: threading.Event, wait_seconds: float = 5.0) -> None:
    """Receive and settle until stopped. A failure is logged and the loop goes on: the queue
    redelivers what was not acknowledged, and the claims make redelivery harmless."""
    while not stop.is_set():
        try:
            service.consume(wait_seconds=wait_seconds)
        except Exception:                                      # pragma: no cover - logged, retried
            logger.exception("settlement-event consumer failed; retrying")
            stop.wait(wait_seconds)


@asynccontextmanager
async def lifespan(app: FastAPI):
    stop = threading.Event()
    service = app.state.audit_service
    worker = None
    if service.broker is not None:
        worker = threading.Thread(target=consume_forever, args=(service, stop), name="settlement-events",
                                  daemon=True)
        worker.start()
        logger.info("consuming settlement events from Service Bus queue %s", service.queue)
    yield
    stop.set()
    if worker is not None:
        worker.join(timeout=10)


_broker, _queue, _lease = event_ports()
app = FastAPI(title="audit-ai-service", lifespan=lifespan)
app.state.audit_service = AuditService(
    source=HttpSettlementClient(os.getenv("SETTLEMENT_API_URL", "http://localhost:8080"),
                                credentials=workload_credentials()),       # SI-01: None unless configured
    rag=PolicyRAGPipeline(POLICY_DIR),
    llm=build_audit_llm(),
    tenant_id=os.getenv("AUDIT_TENANT", "acme"),
    broker=_broker, queue=_queue, lease=_lease,
)
app.include_router(audits_router)


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe used by the container HEALTHCHECK and the platform."""
    return {"status": "UP", "service": "audit-ai-service"}
