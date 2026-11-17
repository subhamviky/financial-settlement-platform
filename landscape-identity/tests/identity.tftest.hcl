# Plans the stack against a mocked azuread provider: no directory and no credentials.
mock_provider "azuread" {}

variables {
  directory_id                  = "00000000-0000-0000-0000-000000000000"
  environment                   = "test"
  tenants                       = ["acme", "globex"]
  o2c_google_service_account_id = "112233445566778899000"
  recon_aws_issuer              = "https://abc123.tokens.sts.global.api.aws"
  recon_aws_subject             = "arn:aws:iam::123456789012:role/recon"
  audit_ai_principal_id         = "11111111-1111-1111-1111-111111111111"
}

run "receivers_have_action_and_tenant_roles" {
  command = plan
  assert {
    condition     = toset([for r in azuread_application.receiver["settlement"].app_role : r.value]) == toset(["settlement.submit", "settlement.adjust", "settlement.read", "tenant:acme", "tenant:globex"])
    error_message = "the settlement app has the three actions and one role per tenant"
  }
  assert {
    condition     = alltrue([for r in azuread_application.receiver["settlement"].app_role : r.allowed_member_types == toset(["Application"])])
    error_message = "the roles are for applications (services), not users"
  }
  assert {
    condition     = azuread_application.receiver["settlement"].api[0].requested_access_token_version == 2
    error_message = "receivers get v2 tokens (one issuer per directory)"
  }
  assert {
    condition     = azuread_service_principal.receiver["settlement"].app_role_assignment_required
    error_message = "only granted callers may get a token for a receiver"
  }
}

run "callers_federate_without_secrets" {
  command = plan
  assert {
    condition     = azuread_application_federated_identity_credential.o2c_google[0].issuer == "https://accounts.google.com" && azuread_application_federated_identity_credential.o2c_google[0].subject == "112233445566778899000"
    error_message = "o2c trusts its Google service account's unique id"
  }
  assert {
    condition     = tolist(azuread_application_federated_identity_credential.recon_aws[0].audiences) == tolist(["api://AzureADTokenExchange"])
    error_message = "the federated audience is Entra's token exchange"
  }
}

run "grants_follow_least_privilege" {
  command = plan
  assert {
    condition     = length(azuread_app_role_assignment.grant) == 14
    error_message = "o2c 4 on settlement and 3 on reconciliation, recon 4, audit-ai 3 (actions plus two tenants each)"
  }
  assert {
    condition     = !contains(keys(azuread_app_role_assignment.grant), "audit-ai->settlement/settlement.submit") && !contains(keys(azuread_app_role_assignment.grant), "recon->settlement/settlement.submit")
    error_message = "only o2c submits settlements"
  }
  assert {
    condition     = contains(keys(azuread_app_role_assignment.grant), "recon->settlement/settlement.adjust") && !contains(keys(azuread_app_role_assignment.grant), "o2c->settlement/settlement.adjust")
    error_message = "only reconciliation adjusts"
  }
}

run "nothing_federated_or_granted_until_the_inputs_exist" {
  command = plan
  variables {
    o2c_google_service_account_id = ""
    recon_aws_issuer              = ""
    audit_ai_principal_id         = ""
  }
  assert {
    condition     = length(azuread_application_federated_identity_credential.o2c_google) == 0 && length(azuread_application_federated_identity_credential.recon_aws) == 0
    error_message = "no federated credential without its subject"
  }
  assert {
    condition     = length([for k in keys(azuread_app_role_assignment.grant) : k if startswith(k, "audit-ai")]) == 0
    error_message = "no audit-ai grant without its principal id"
  }
}
