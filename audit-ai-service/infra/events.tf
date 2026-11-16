# Settlement events from settlement-service, on this service's Service Bus queue (main.tf). Added
# by hand next to the A2C-generated main.tf. The queue belongs to its consumer: the publisher is
# granted Sender on this one queue, and nothing else.

variable "event_publisher_principal_ids" {
  type        = list(string)
  description = "Principal ids allowed to send to the settlement-events queue: settlement-service's managed identity (its output events_publisher_principal_id)."
  default     = []
}

resource "azurerm_role_assignment" "event_publishers" {
  for_each             = toset(var.event_publisher_principal_ids)
  scope                = azurerm_servicebus_queue.events.id
  role_definition_name = "Azure Service Bus Data Sender"
  principal_id         = each.value
}

output "settlement_events_namespace" {
  description = "Give this to settlement-service (its audit_events_namespace variable)."
  value       = "${azurerm_servicebus_namespace.app.name}.servicebus.windows.net"
}

output "settlement_events_queue" {
  description = "Give this to settlement-service (its audit_events_queue variable)."
  value       = azurerm_servicebus_queue.events.name
}
