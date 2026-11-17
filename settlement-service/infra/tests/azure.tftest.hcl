# Plans the configuration against a mocked azurerm provider: no subscription
# and no credentials. Data sources return random strings unless given defaults.
mock_provider "azurerm" {
  mock_data "azurerm_client_config" {
    defaults = {
      tenant_id = "00000000-0000-0000-0000-000000000000"
      object_id = "22222222-2222-2222-2222-222222222222"
    }
  }
}

variables {
  subscription_id   = "00000000-0000-0000-0000-000000000000"
  db_admin_password = "x"
}

run "plan_with_mocks" {
  command = plan

  assert {
    condition     = azurerm_servicebus_queue.events.dead_lettering_on_message_expiration
    error_message = "Expired messages must go to the dead-letter sub-queue."
  }

  assert {
    condition     = azurerm_postgresql_flexible_server.db.version == "16" && azurerm_postgresql_flexible_server.db.backup_retention_days == 7
    error_message = "The settlement database must be PostgreSQL 16 with 7 days of backups."
  }

  assert {
    condition     = azurerm_postgresql_flexible_server_database.settlement.name == "settlement"
    error_message = "The application database must be named settlement."
  }
}

run "outbox_publishes_to_audit_queue" {
  command = plan

  variables {
    audit_events_namespace = "sb-audit-ai-service-dev.servicebus.windows.net"
    audit_events_queue     = "settlement-events"
  }

  assert {
    condition     = contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value == null ? "" : e.value}"], "SETTLEMENT_EVENTS_SERVICEBUS_QUEUE=settlement-events")
    error_message = "With audit-ai's queue configured, the outbox relay sends to it."
  }
}

run "outbox_only_logs_without_a_queue" {
  command = plan

  assert {
    condition     = !contains([for e in azurerm_container_app.app.template[0].container[0].env : e.name], "SETTLEMENT_EVENTS_SERVICEBUS_QUEUE")
    error_message = "Without a queue, the relay logs and audit-ai reads the feed."
  }
}

run "service_callers_are_verified_at_ingress" {
  command = plan
  assert {
    condition = alltrue([for kv in ["E2A_IDENTITY_SOURCE=azure_container_apps", "E2A_IDENTITY_TENANTS_FROM_ROLES=tenant:"] :
    contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value == null ? "" : e.value}"], kv)])
    error_message = "settlement-service must take its callers from Container Apps authentication, with tenants granted as roles"
  }
  assert {
    condition     = contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value == null ? "" : e.value}"], "E2A_IDENTITY_TRUSTED_ISSUERS=[\"https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0\"]")
    error_message = "only the landscape directory's v2 issuer is trusted"
  }
}

run "identity_can_be_switched_off_for_a_test_deployment" {
  command = plan
  variables {
    identity_enabled = false
  }
  assert {
    condition     = !contains([for e in azurerm_container_app.app.template[0].container[0].env : e.name], "E2A_IDENTITY_SOURCE")
    error_message = "identity_enabled = false must leave identity off"
  }
}

run "the_service_reaches_its_database" {
  command = plan
  assert {
    condition = alltrue([for kv in ["SPRING_PROFILES_ACTIVE=prod", "DB_USER=settlementadmin"] :
    contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value == null ? "" : e.value}"], kv)])
    error_message = "settlement-service runs its prod profile against PostgreSQL"
  }
  assert {
    condition     = one([for e in azurerm_container_app.app.template[0].container[0].env : e.secret_name if e.name == "DB_PASSWORD"]) == "db-password"
    error_message = "the password comes from a Container App secret"
  }
  assert {
    condition     = one(azurerm_container_app.app.secret).name == "db-password"
    error_message = "the secret is a Key Vault reference read with the app's identity"
  }
  assert {
    condition     = one([for e in azurerm_container_app.app.template[0].container[0].env : e.value if e.name == "DB_PASSWORD"]) == null
    error_message = "the password value is never a plain setting"
  }
}
