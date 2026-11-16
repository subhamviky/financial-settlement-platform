"""Who may call the API: identity at ingress (E2A v1.7.4, hub design note MP-05, N3).

In Azure, Container Apps authentication (Entra ID) verifies the caller's token and injects the
verified claims in X-MS-CLIENT-PRINCIPAL ("External requests aren't allowed to set these
headers"); e2a-adapters reads them and E2A's ClaimMapIdentityResolver resolves them with the
Entra ID preset (tid, oid, roles). Configured by environment:

  E2A_IDENTITY_SOURCE          azure_container_apps (infra sets it); unset = off (local, tests)
  E2A_IDENTITY_TENANT_MAP      JSON {"<Entra directory id>": "<tenant>"}: the directory's tenant
  E2A_IDENTITY_CLAIM_MAP       JSON claim map (default: the Entra ID preset)
  E2A_IDENTITY_TRUSTED_ISSUERS JSON list of accepted issuers (optional)
  AUDIT_REQUIRED_ROLE          an app role every caller must carry (optional)

This deployment audits one tenant (AUDIT_TENANT): the claims must resolve to it. Fail-closed
when configured: no verified claims 401; another tenant or a missing role 403.
"""
import os

from e2a import IdentityRefused
from fastapi import HTTPException, Request


def _config() -> dict:
    from e2a_adapters.identity import preset
    return {"identity_source": os.getenv("E2A_IDENTITY_SOURCE") or None,
            **({} if os.getenv("E2A_IDENTITY_CLAIM_MAP") else {"identity_claim_map": preset("entra_id")})}


def require_identity(request: Request) -> None:
    from e2a_adapters.ingress import identity_at_ingress
    tenant = request.app.state.audit_service.tenant_id
    try:
        identity = identity_at_ingress(_config(), headers=request.headers, request_tenant=tenant)
    except IdentityRefused as refused:
        raise HTTPException(status_code=401 if "not verified" in str(refused) else 403, detail=str(refused))
    except ValueError as malformed:
        raise HTTPException(status_code=401, detail=str(malformed))
    required = os.getenv("AUDIT_REQUIRED_ROLE")
    if identity is not None and required and required not in identity.roles:
        raise HTTPException(status_code=403, detail=f"the credentials do not carry the role {required!r}")
    request.state.identity = identity
