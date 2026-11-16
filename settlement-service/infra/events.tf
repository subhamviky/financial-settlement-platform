# The outbox relay sends settlement events to audit-ai-service's Service Bus queue. Added by hand
# next to the A2C-generated main.tf. The queue is audit-ai's (its infra grants this service's
# identity Sender on it): set the two variables from audit-ai's outputs and pass this module's
# events_publisher_principal_id to audit-ai's event_publisher_principal_ids. Unset, the relay
# only logs, and audit-ai reads the event feed API.

variable "audit_events_namespace" {
  type        = string
  description = "audit-ai's Service Bus namespace, <name>.servicebus.windows.net (its output settlement_events_namespace)."
  default     = ""
}

variable "audit_events_queue" {
  type        = string
  description = "audit-ai's settlement-events queue (its output settlement_events_queue)."
  default     = ""
}

locals {
  # Spring binds SETTLEMENT_EVENTS_SERVICEBUS_* to settlement.events.servicebus.* (EventPublisherConfig);
  # AZURE_CLIENT_ID makes DefaultAzureCredential use the user-assigned identity. No secret.
  event_publisher_env = var.audit_events_queue == "" ? {} : {
    SETTLEMENT_EVENTS_SERVICEBUS_NAMESPACE = var.audit_events_namespace
    SETTLEMENT_EVENTS_SERVICEBUS_QUEUE     = var.audit_events_queue
    AZURE_CLIENT_ID                        = azurerm_user_assigned_identity.app.client_id
  }
}

output "events_publisher_principal_id" {
  description = "Give this to audit-ai (its event_publisher_principal_ids variable)."
  value       = azurerm_user_assigned_identity.app.principal_id
}
