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

# ------------------------------------------------- the Container App's connection (G-5)
# The service runs its `prod` profile (application-prod.yml) against this server: the URL and user
# are plain settings; the password is a Key Vault secret that the Container App reads with its
# user-assigned identity (Key Vault Secrets User, main.tf), so it is never an app setting.

variable "db_allow_azure_services" {
  type        = bool
  description = "Open the server's firewall to Azure services (the Container App's outbound addresses are not fixed). Replace with VNet integration for a private network."
  default     = true
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  count            = var.db_allow_azure_services ? 1 : 0
  name             = "allow-azure-services"
  server_id        = azurerm_postgresql_flexible_server.db.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# Terraform writes the secret, so the identity running it needs to manage secrets in this vault.
resource "azurerm_role_assignment" "deployer_secrets" {
  scope                = azurerm_key_vault.app.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_secret" "db_password" {
  name         = "db-password"
  value        = var.db_admin_password
  key_vault_id = azurerm_key_vault.app.id
  depends_on   = [azurerm_role_assignment.deployer_secrets]
}

locals {
  database_env = {
    SPRING_PROFILES_ACTIVE = "prod"
    DB_URL                 = "jdbc:postgresql://${azurerm_postgresql_flexible_server.db.fqdn}:5432/${azurerm_postgresql_flexible_server_database.settlement.name}?sslmode=require"
    DB_USER                = var.db_admin_login
  }
}
