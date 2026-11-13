# State of the service (event claims, findings, the feed position) in Cosmos DB, through
# e2a-adapters' Cosmos DB adapters (design note D-03). The Container App runs up to three
# replicas; with this they share one state instead of each keeping its own in memory.
# Added by hand next to the A2C-generated main.tf, which stays untouched apart from the
# container's environment.

resource "azurerm_cosmosdb_account" "state" {
  name                = "cosmos-${local.name}"
  location            = azurerm_resource_group.app.location
  resource_group_name = azurerm_resource_group.app.name
  offer_type          = "Standard"
  kind                = "GlobalDocumentDB"
  tags                = local.tags

  # The claim decides on what another replica wrote: read it strongly (single region).
  consistency_policy {
    consistency_level = "Strong"
  }

  geo_location {
    location          = azurerm_resource_group.app.location
    failover_priority = 0
  }

  capabilities {
    name = "EnableServerless"
  }

  # The service authenticates with its managed identity; account keys are off.
  local_authentication_enabled = false

  backup {
    type = "Continuous"
    tier = "Continuous7Days"
  }
}

resource "azurerm_cosmosdb_sql_database" "audit" {
  name                = "audit"
  resource_group_name = azurerm_resource_group.app.name
  account_name        = azurerm_cosmosdb_account.state.name
}

resource "azurerm_cosmosdb_sql_container" "state" {
  name                = "state"
  resource_group_name = azurerm_resource_group.app.name
  account_name        = azurerm_cosmosdb_account.state.name
  database_name       = azurerm_cosmosdb_sql_database.audit.name
  partition_key_paths = ["/pk"] # each item is its own logical partition (the adapters' layout)
  default_ttl         = -1      # TTL on, per item: claims set "ttl" for garbage collection only
}

# Least privilege: the built-in data-plane "Cosmos DB Built-in Data Contributor" on this account.
resource "azurerm_cosmosdb_sql_role_assignment" "app" {
  resource_group_name = azurerm_resource_group.app.name
  account_name        = azurerm_cosmosdb_account.state.name
  role_definition_id  = "${azurerm_cosmosdb_account.state.id}/sqlRoleDefinitions/00000000-0000-0000-0000-000000000002"
  principal_id        = azurerm_user_assigned_identity.app.principal_id
  scope               = azurerm_cosmosdb_account.state.id
}

output "cosmos_endpoint" {
  value = azurerm_cosmosdb_account.state.endpoint
}
