# Service identity at ingress (hub design note SI-01). The callers are services in three clouds,
# each with an Entra access token for the settlement app (landscape-identity/ creates the apps,
# roles and grants). Container Apps authentication verifies the token and hands the verified
# claims to the service (X-MS-CLIENT-PRINCIPAL); e2a-java's IdentityAtIngress then requires:
#   - X-Tenant-Id among the caller's granted tenants (app roles "tenant:<id>");
#   - the route's action role (settlement.submit, settlement.adjust, settlement.read).
# /actuator/health stays open. Fail-closed: with identity on and authentication not configured,
# every /api call is refused (401).
#
# azurerm has no resource for Container Apps authentication; configure it once after apply
# (the values are the outputs of landscape-identity/):
#   az containerapp auth microsoft update -g <resource group> -n <container app> \
#     --client-id <settlement_app_client_id> \
#     --issuer https://login.microsoftonline.com/<directory>/v2.0 \
#     --allowed-audiences <settlement_app_id_uri> --yes
#   az containerapp auth update -g <resource group> -n <container app> \
#     --unauthenticated-client-action Return401 --excluded-paths /actuator/health

variable "identity_enabled" {
  type        = bool
  description = "Require a verified service caller on every /api call (fail-closed). Disable only for a test deployment."
  default     = true
}

variable "identity_directory_id" {
  type        = string
  description = "The Entra directory that issues the landscape's workload tokens. Empty: the directory Terraform runs in."
  default     = ""
}

locals {
  identity_directory = var.identity_directory_id != "" ? var.identity_directory_id : data.azurerm_client_config.current.tenant_id
  identity_env = var.identity_enabled ? {
    E2A_IDENTITY_SOURCE             = "azure_container_apps"
    E2A_IDENTITY_TENANTS_FROM_ROLES = "tenant:"
    E2A_IDENTITY_TRUSTED_ISSUERS    = jsonencode(["https://login.microsoftonline.com/${local.identity_directory}/v2.0"])
  } : {}
}
