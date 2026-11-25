variable "project_id" {
  type        = string
  description = "Google Cloud project id (deploy/gcp/binding.vars)."
}

variable "region" {
  type        = string
  description = "Region: the Always Free e2-micro exists only in us-west1, us-central1 and us-east1."
  default     = "us-central1"

  validation {
    condition     = contains(["us-west1", "us-central1", "us-east1"], var.region)
    error_message = "The ledger VM is free only in us-west1, us-central1 or us-east1."
  }
}

variable "image" {
  type        = string
  description = "settlement-service image in the landscape's Artifact Registry, pushed by CI."
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "postgres_image" {
  type        = string
  description = "postgres image mirrored into the landscape's Artifact Registry (the VM has no internet)."
  default     = "us-central1-docker.pkg.dev/PLACEHOLDER/landscape/postgres:16-alpine"
}

variable "events_topic" {
  type        = string
  description = "Pub/Sub topic of settlement events (audit-ai subscribes to it)."
  default     = "settlement-events"
}

variable "max_instance_count" {
  type        = number
  description = "Cloud Run instances at most (1 keeps it free and inside the database's connections)."
  default     = 1
}

variable "schedule" {
  type        = string
  description = "Cron of the maintenance job (outbox relay, stuck-settlement recovery)."
  default     = "*/10 * * * *"
}

variable "invoker_members" {
  type        = list(string)
  description = "Principals allowed to call the service: O2C's, reconciliation's, audit-ai's and the assistant's service accounts."
  default     = []

  validation {
    condition     = !contains(var.invoker_members, "allUsers") && !contains(var.invoker_members, "allAuthenticatedUsers")
    error_message = "settlement-service moves money: it is never public."
  }
}
