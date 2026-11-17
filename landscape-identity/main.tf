# landscape-identity — the Entra side of service-to-service identity (hub design note SI-01).
#
# Entra ID is the landscape's single workload issuer. This stack creates, in one directory:
#   receivers  settlement-service and reconciliation-service: one app each, with app roles for
#              the actions they allow and one "tenant:<id>" role per tenant;
#   callers    o2c-agent-service (Google Cloud) and reconciliation-service (AWS): one app each,
#              with a federated credential that trusts its cloud's own token (no secret);
#              audit-ai-service (Azure): its managed identity, already an Entra principal;
#   grants     which caller may do what, for which tenants (app role assignments).
# A caller exchanges its native token for an Entra access token for the receiver's app
# (e2a_adapters.workload); the receiver checks the roles in it (identity at ingress).
#
# Inputs come from the other stacks' outputs; the outputs here go to each service's environment.
# No secret is created or stored.

terraform {
  required_version = ">= 1.7"
  required_providers {
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 3.0"
    }
  }
}

provider "azuread" {
  tenant_id = var.directory_id
}

variable "directory_id" {
  type        = string
  description = "The Entra directory (tenant id) that issues the landscape's workload tokens."
}

variable "environment" {
  type        = string
  description = "Deployment environment name (dev, test, prod)."
}

variable "tenants" {
  type        = list(string)
  description = "The landscape's tenants (E2A tenant ids). Each gets a role tenant:<id> on every receiver."
  default     = ["acme"]
}

variable "o2c_google_service_account_id" {
  type        = string
  description = "The numeric unique id (not the e-mail) of o2c-agent-service's Cloud Run service account. Empty: no federated credential yet."
  default     = ""
}

variable "recon_aws_issuer" {
  type        = string
  description = "The issuer URL of the AWS account's outbound identity federation (aws iam get-outbound-web-identity-federation-info). Empty: no federated credential yet."
  default     = ""
}

variable "recon_aws_subject" {
  type        = string
  description = "The sub claim of reconciliation-service's web identity token (its IAM role, as STS issues it)."
  default     = ""
}

variable "audit_ai_principal_id" {
  type        = string
  description = "audit-ai-service's managed identity principal (object) id: its stack's output identity_principal_id. Empty: no grant yet."
  default     = ""
}

locals {
  name           = "landscape-${var.environment}"
  token_exchange = "api://AzureADTokenExchange"

  receivers = {
    settlement     = { actions = ["settlement.submit", "settlement.adjust", "settlement.read"] }
    reconciliation = { actions = ["reconciliation.read"] }
  }
  # every role of every receiver: actions, and one tenant role per tenant
  roles = {
    for pair in flatten([
      for receiver, r in local.receivers : [
        for value in concat(r.actions, [for t in var.tenants : "tenant:${t}"]) : { receiver = receiver, value = value }
      ]
    ]) : "${pair.receiver}/${pair.value}" => pair
  }

  # who may do what (the tenants are all of var.tenants for each grant)
  grants = {
    o2c        = { settlement = ["settlement.submit", "settlement.read"], reconciliation = ["reconciliation.read"] }
    recon      = { settlement = ["settlement.adjust", "settlement.read"] }
    "audit-ai" = { settlement = ["settlement.read"] }
  }
  assignments = {
    for a in flatten([
      for caller, by_receiver in local.grants : [
        for receiver, actions in by_receiver : [
          for value in concat(actions, [for t in var.tenants : "tenant:${t}"]) : { caller = caller, receiver = receiver, value = value }
        ]
      ]
    ]) : "${a.caller}->${a.receiver}/${a.value}" => a
    if a.caller != "audit-ai" || var.audit_ai_principal_id != ""
  }
  caller_principals = {
    o2c        = azuread_service_principal.caller["o2c"].object_id
    recon      = azuread_service_principal.caller["recon"].object_id
    "audit-ai" = var.audit_ai_principal_id
  }
}

# ---------------------------------------------------------------- receivers
resource "azuread_application" "receiver" {
  for_each         = local.receivers
  display_name     = "${local.name}-${each.key}"
  identifier_uris  = ["api://${local.name}-${each.key}"]
  sign_in_audience = "AzureADMyOrg"

  api {
    requested_access_token_version = 2 # v2 tokens: issuer https://login.microsoftonline.com/<directory>/v2.0
  }

  dynamic "app_role" {
    for_each = { for k, r in local.roles : k => r if r.receiver == each.key }
    content {
      allowed_member_types = ["Application"]
      id                   = uuidv5("url", "https://e2a.landscape/${local.name}/${app_role.key}")
      value                = app_role.value.value
      display_name         = app_role.value.value
      description          = startswith(app_role.value.value, "tenant:") ? "May act for tenant ${trimprefix(app_role.value.value, "tenant:")}" : "May call ${app_role.value.value}"
      enabled              = true
    }
  }
}

resource "azuread_service_principal" "receiver" {
  for_each                     = local.receivers
  client_id                    = azuread_application.receiver[each.key].client_id
  app_role_assignment_required = true # only granted callers get a token for it
}

# ---------------------------------------------------------------- callers in other clouds
resource "azuread_application" "caller" {
  for_each         = toset(["o2c", "recon"])
  display_name     = "${local.name}-${each.key}-caller"
  sign_in_audience = "AzureADMyOrg"
}

resource "azuread_service_principal" "caller" {
  for_each  = azuread_application.caller
  client_id = each.value.client_id
}

# o2c-agent-service: a Google ID token for its Cloud Run service account
resource "azuread_application_federated_identity_credential" "o2c_google" {
  count          = var.o2c_google_service_account_id == "" ? 0 : 1
  application_id = azuread_application.caller["o2c"].id
  display_name   = "o2c-google-cloud-run"
  description    = "o2c-agent-service's Cloud Run service account (Google ID token)"
  audiences      = [local.token_exchange]
  issuer         = "https://accounts.google.com"
  subject        = var.o2c_google_service_account_id
}

# reconciliation-service: an AWS STS web identity token (IAM outbound identity federation)
resource "azuread_application_federated_identity_credential" "recon_aws" {
  count          = var.recon_aws_issuer == "" ? 0 : 1
  application_id = azuread_application.caller["recon"].id
  display_name   = "recon-aws-iam-role"
  description    = "reconciliation-service's IAM role (STS GetWebIdentityToken)"
  audiences      = [local.token_exchange]
  issuer         = var.recon_aws_issuer
  subject        = var.recon_aws_subject
}

# ---------------------------------------------------------------- grants
resource "azuread_app_role_assignment" "grant" {
  for_each            = local.assignments
  principal_object_id = local.caller_principals[each.value.caller]
  resource_object_id  = azuread_service_principal.receiver[each.value.receiver].object_id
  app_role_id         = uuidv5("url", "https://e2a.landscape/${local.name}/${each.value.receiver}/${each.value.value}")
}

# ---------------------------------------------------------------- outputs
output "issuer" {
  description = "The v2 issuer every receiver trusts (E2A_IDENTITY_TRUSTED_ISSUERS; reconciliation's service authorizer)."
  value       = "https://login.microsoftonline.com/${var.directory_id}/v2.0"
}

output "settlement_app_client_id" {
  description = "settlement-service's Container Apps authentication client id."
  value       = azuread_application.receiver["settlement"].client_id
}

output "settlement_scope" {
  description = "The scope callers ask for (E2A_WORKLOAD_TOKEN_SCOPES)."
  value       = "api://${local.name}-settlement/.default"
}

output "reconciliation_audience" {
  description = "The audience of reconciliation's service-route authorizer (the app's client id: v2 tokens carry it as aud)."
  value       = azuread_application.receiver["reconciliation"].client_id
}

output "reconciliation_scope" {
  value = "api://${local.name}-reconciliation/.default"
}

output "o2c_client_id" {
  description = "o2c-agent-service's E2A_WORKLOAD_ENTRA_CLIENT_ID."
  value       = azuread_application.caller["o2c"].client_id
}

output "recon_client_id" {
  description = "reconciliation-service's E2A_WORKLOAD_ENTRA_CLIENT_ID."
  value       = azuread_application.caller["recon"].client_id
}
