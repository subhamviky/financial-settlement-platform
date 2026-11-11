# Azure Database for PostgreSQL (Flexible Server) for settlement-service.
# Added by hand next to the A2C-generated main.tf, reusing its resource group,
# naming (local.name) and tags. The admin password is a variable with no
# default: it comes from a secret store at deploy time and is never committed.

variable "db_admin_login" {
  type        = string
  description = "PostgreSQL administrator login."
  default     = "settlementadmin"
}

variable "db_admin_password" {
  type        = string
  description = "PostgreSQL administrator password, supplied at deploy time."
  sensitive   = true
}

variable "db_sku" {
  type        = string
  description = "Flexible Server SKU."
  default     = "B_Standard_B1ms"
}

resource "azurerm_postgresql_flexible_server" "db" {
  name                   = "psql-${local.name}"
  resource_group_name    = azurerm_resource_group.app.name
  location               = azurerm_resource_group.app.location
  version                = "16"
  sku_name               = var.db_sku
  storage_mb             = 32768
  backup_retention_days  = 7
  administrator_login    = var.db_admin_login
  administrator_password = var.db_admin_password
  tags                   = local.tags
}

resource "azurerm_postgresql_flexible_server_database" "settlement" {
  name      = "settlement"
  server_id = azurerm_postgresql_flexible_server.db.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

output "db_host" {
  value = azurerm_postgresql_flexible_server.db.fqdn
}

output "db_name" {
  value = azurerm_postgresql_flexible_server_database.settlement.name
}
