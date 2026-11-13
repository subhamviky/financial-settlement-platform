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
  subscription_id = "00000000-0000-0000-0000-000000000000"
}

run "plan_with_mocks" {
  command = plan

  assert {
    condition     = azurerm_servicebus_queue.events.dead_lettering_on_message_expiration
    error_message = "Expired messages must go to the dead-letter sub-queue."
  }

  assert {
    condition     = contains([for e in azurerm_container_app.app.template[0].container[0].env : "${e.name}=${e.value}"], "AUDIT_STATE_BACKEND=cosmos")
    error_message = "Every replica must keep its claims, findings and feed position in Cosmos DB, not in memory."
  }

  assert {
    condition     = azurerm_cosmosdb_account.state.consistency_policy[0].consistency_level == "Strong" && !azurerm_cosmosdb_account.state.local_authentication_enabled
    error_message = "Claims read what other replicas wrote (Strong), with the managed identity only (no keys)."
  }

  assert {
    condition     = azurerm_cosmosdb_sql_container.state.partition_key_paths == tolist(["/pk"]) && azurerm_cosmosdb_sql_container.state.default_ttl == -1
    error_message = "The adapters' layout: partition key /pk, per-item TTL enabled."
  }

  assert {
    condition     = azurerm_container_app.app.ingress[0].target_port == 8000
    error_message = "Ingress must send traffic to the port the Python image serves on (8000)."
  }
}
