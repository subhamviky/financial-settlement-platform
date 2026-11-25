"""Google Cloud (hub design note GCP-01): settlement events pushed by Pub/Sub.

POST /pubsub/settlement-events takes one push request and answers 204 when the event is done
(audited, a duplicate, or dead-lettered with its reason to <subscription>-dlq) and 500 to have
Pub/Sub deliver it again. Only the push subscription's service account may call it (Cloud Run IAM).
"""
from fastapi import APIRouter, HTTPException, Request, Response

router = APIRouter()


@router.post("/pubsub/settlement-events")
async def settlement_event_push(request: Request) -> Response:
    service = request.app.state.audit_service
    if service.broker is None:
        raise HTTPException(503, "Pub/Sub is not configured (AUDIT_PUBSUB_SUBSCRIPTION, GOOGLE_CLOUD_PROJECT)")
    try:
        status, _ = service.consume_push(await request.json())
    except ValueError as error:                      # not a push envelope: Pub/Sub never sends one
        raise HTTPException(400, str(error))
    return Response(status_code=status)
