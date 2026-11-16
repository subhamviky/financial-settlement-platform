# Plans the configuration against a mocked azurerm provider: no subscription
# and no credentials. Data sources return random strings unless given defaults.
mock_provider "azurerm" {
  mock_data "azurerm_client_config" {
    defaults = {
      tenant_id = "00000000-0000-0000-0000-000000000000"
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
    condition     = contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value}"], "SETTLEMENT_EVENTS_SERVICEBUS_QUEUE=settlement-events")
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
