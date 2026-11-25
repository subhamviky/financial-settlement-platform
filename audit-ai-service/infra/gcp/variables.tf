variable "project_id" {
  type        = string
  description = "Google Cloud project id (deploy/gcp/binding.vars)."
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "image" {
  type        = string
  description = "audit-ai-service image in the landscape's Artifact Registry, pushed by CI."
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "firestore_database" {
  type    = string
  default = "(default)"
}

variable "settlement_events_topic" {
  type        = string
  description = "The topic settlement-service publishes to (its infra/gcp output events_topic)."
  default     = "settlement-events"
}

variable "settlement_api_url" {
  type        = string
  description = "Cloud Run URL of settlement-service (the event feed and the system of record)."
  default     = "https://settlement-service.placeholder.run.app"
}

variable "tenant" {
  type    = string
  default = "acme"
}

variable "llm_profile" {
  type        = string
  description = "E2A provider profile: simulated (no key) or gemini_api (free tier)."
  default     = "simulated"

  validation {
    condition     = contains(["simulated", "gemini_api"], var.llm_profile)
    error_message = "Free tier only: simulated or gemini_api."
  }
}

variable "llm_model_id" {
  type    = string
  default = ""
}

variable "gemini_api_key_secret" {
  type        = string
  description = "Secret Manager secret id holding the Gemini API key (empty with the simulated profile)."
  default     = ""
}

variable "max_instance_count" {
  type    = number
  default = 1
}

variable "max_delivery_attempts" {
  type    = number
  default = 5
}

variable "invoker_members" {
  type        = list(string)
  description = "Other principals allowed to call the service (the assistant's service account)."
  default     = []

  validation {
    condition     = !contains(var.invoker_members, "allUsers") && !contains(var.invoker_members, "allAuthenticatedUsers")
    error_message = "audit-ai-service is never public."
  }
}

variable "identity_subject_roles" {
  type        = map(list(string))
  description = "Who may call the API (GCP-01, E2A v1.11.0): each caller's email -> roles, this tenant as \"tenant:<id>\". Empty: nobody passes (Pub/Sub push does not go through it)."
  default     = {}
}
