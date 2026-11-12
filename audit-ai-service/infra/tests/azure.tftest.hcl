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
    condition     = azurerm_container_app.app.ingress[0].target_port == 8000
    error_message = "Ingress must send traffic to the port the Python image serves on (8000)."
  }
}
