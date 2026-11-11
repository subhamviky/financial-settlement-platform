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
