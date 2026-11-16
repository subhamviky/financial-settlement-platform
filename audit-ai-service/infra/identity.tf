# Identity at ingress (E2A v1.7.4): Container Apps authentication with Entra ID verifies every
# caller and injects the verified claims (X-MS-CLIENT-PRINCIPAL); the service takes the caller
# from them only (E2A_IDENTITY_SOURCE=azure_container_apps) and maps the caller's directory to
# this deployment's tenant. /health stays open. Fail-closed: with identity on and the
# authentication not configured, every /audits call is refused (401).
#
# azurerm has no resource for Container Apps authentication; configure it once after apply:
#   az containerapp auth microsoft update -g <resource group> -n <container app> \
#     --client-id <app registration client id> --issuer https://login.microsoftonline.com/<directory>/v2.0 \
#     --yes
#   az containerapp auth update -g <resource group> -n <container app> \
#     --unauthenticated-client-action Return401 --excluded-paths /health

variable "audit_tenant" {
  type        = string
  description = "The tenant this deployment audits (AUDIT_TENANT)."
  default     = "acme"
}

variable "identity_enabled" {
  type        = bool
  description = "Take the caller from Container Apps authentication (fail-closed). Disable only for a test deployment."
  default     = true
}

variable "identity_directory_id" {
  type        = string
  description = "The Entra directory whose users belong to audit_tenant. Empty: the directory Terraform runs in."
  default     = ""
}

variable "required_role" {
  type        = string
  description = "An app role every caller must carry (AUDIT_REQUIRED_ROLE); empty: any user of the directory."
  default     = ""
}

locals {
  identity_directory = var.identity_directory_id != "" ? var.identity_directory_id : data.azurerm_client_config.current.tenant_id
  identity_env = var.identity_enabled ? {
    AUDIT_TENANT            = var.audit_tenant
    E2A_IDENTITY_SOURCE     = "azure_container_apps"
    E2A_IDENTITY_TENANT_MAP = jsonencode({ (local.identity_directory) = var.audit_tenant })
    AUDIT_REQUIRED_ROLE     = var.required_role
    } : {
    AUDIT_TENANT = var.audit_tenant
  }
}
