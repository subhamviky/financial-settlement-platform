# Calls to settlement-service carry audit-ai's own identity (hub design note SI-01): its
# user-assigned managed identity gets an Entra token for the settlement app (e2a_adapters.workload,
# E2A_WORKLOAD_IDENTITY=azure). landscape-identity/ grants it settlement.read and its tenants;
# give that stack this stack's output identity_principal_id.

variable "settlement_api_url" {
  type        = string
  description = "settlement-service's base URL (its app_fqdn, as https://...)."
  default     = ""
}

variable "settlement_scope" {
  type        = string
  description = "The settlement app's scope (landscape-identity's output settlement_scope). Empty: calls carry no token."
  default     = ""
}

locals {
  workload_env = merge(
    var.settlement_api_url == "" ? {} : { SETTLEMENT_API_URL = var.settlement_api_url },
    var.settlement_scope == "" || var.settlement_api_url == "" ? {} : {
      E2A_WORKLOAD_IDENTITY        = "azure"
      E2A_WORKLOAD_AZURE_CLIENT_ID = azurerm_user_assigned_identity.app.client_id
      E2A_WORKLOAD_TOKEN_SCOPES    = jsonencode({ (var.settlement_api_url) = var.settlement_scope })
  })
}

output "identity_principal_id" {
  description = "audit-ai's managed identity principal id (landscape-identity's audit_ai_principal_id)."
  value       = azurerm_user_assigned_identity.app.principal_id
}
