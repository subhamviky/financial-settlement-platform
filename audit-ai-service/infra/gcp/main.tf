# audit-ai-service on Google Cloud, free tier only (hub design note GCP-01).
#
# Cloud Run (scale to zero, one instance), state in the landscape's shared Firestore database
# (collection "audit"), settlement events PUSHED by Pub/Sub from settlement-service's topic (no
# background worker), dead letters to <subscription>-dlq. Apply settlement-service's infra/gcp
# first: it creates the topic. Azure stays in ../. No secrets here.

terraform {
  required_version = ">= 1.7"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

data "google_project" "this" {}

locals {
  name         = "audit-ai-service"
  labels       = { app = "audit-ai-service", landscape = "finance-ai", managed-by = "terraform" }
  subscription = "audit-settlement-events"
  pubsub_agent = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
  # GCP-01, E2A v1.11.0: Cloud Run IAM verifies each caller's Google ID token; the deployment grants
  # callers this tenant and their roles. Reads of settlement's feed carry this service's ID token.
  identity_env = {
    E2A_IDENTITY_SOURCE          = "gcp_cloud_run"
    E2A_IDENTITY_TRUSTED_ISSUERS = jsonencode(["https://accounts.google.com"])
    E2A_IDENTITY_SUBJECT_ROLES   = jsonencode(var.identity_subject_roles)
    E2A_WORKLOAD_IDENTITY        = "gcp_id_token"
  }
  llm_env = merge(
    { E2A_PROVIDER_PROFILE = var.llm_profile },
    var.llm_model_id == "" ? {} : { LLM_MODEL_ID = var.llm_model_id },
  )
}

resource "google_service_account" "app" {
  account_id   = "audit-app"
  display_name = "audit-ai-service runtime"
}

resource "google_service_account" "push" {
  account_id   = "audit-push"
  display_name = "Pub/Sub push to audit-ai-service"
}

resource "google_project_iam_member" "firestore" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.app.email}"
}

# ------------------------------------------------------------------ Pub/Sub
resource "google_pubsub_topic" "dlq" {
  name                       = "${local.subscription}-dlq"
  labels                     = local.labels
  message_retention_duration = "604800s"
}

resource "google_pubsub_subscription" "events" {
  name                       = local.subscription
  topic                      = "projects/${var.project_id}/topics/${var.settlement_events_topic}"
  ack_deadline_seconds       = 60
  message_retention_duration = "86400s"
  labels                     = local.labels

  push_config {
    push_endpoint = "${google_cloud_run_v2_service.app.uri}/pubsub/settlement-events"
    oidc_token {
      service_account_email = google_service_account.push.email
      audience              = google_cloud_run_v2_service.app.uri
    }
  }

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dlq.id
    max_delivery_attempts = var.max_delivery_attempts
  }

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "300s"
  }
}

resource "google_pubsub_subscription" "dlq" {
  name                       = "${local.subscription}-dlq"
  topic                      = google_pubsub_topic.dlq.id
  ack_deadline_seconds       = 60
  message_retention_duration = "604800s"
  labels                     = local.labels
}

resource "google_pubsub_topic_iam_member" "app_dead_letters" {
  topic  = google_pubsub_topic.dlq.id
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.app.email}"
}

resource "google_pubsub_subscription_iam_member" "app_reads_dead_letters" {
  subscription = google_pubsub_subscription.dlq.id
  role         = "roles/pubsub.subscriber"
  member       = "serviceAccount:${google_service_account.app.email}"
}

resource "google_pubsub_topic_iam_member" "agent_dead_letters" {
  topic  = google_pubsub_topic.dlq.id
  role   = "roles/pubsub.publisher"
  member = local.pubsub_agent
}

resource "google_pubsub_subscription_iam_member" "agent_acks_source" {
  subscription = google_pubsub_subscription.events.id
  role         = "roles/pubsub.subscriber"
  member       = local.pubsub_agent
}

# ------------------------------------------------------------------ Cloud Run
resource "google_cloud_run_v2_service" "app" {
  name                = local.name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL" # Pub/Sub push calls it; IAM decides who may
  deletion_protection = false
  labels              = local.labels

  template {
    service_account = google_service_account.app.email
    timeout         = "60s"
    scaling {
      min_instance_count = 0
      max_instance_count = var.max_instance_count
    }
    containers {
      image = var.image
      ports {
        container_port = 8000
      }
      resources {
        cpu_idle = true
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
      dynamic "env" {
        for_each = merge(local.llm_env, local.identity_env, {
          AUDIT_STATE_BACKEND        = "firestore"
          AUDIT_FIRESTORE_COLLECTION = "audit"
          FIRESTORE_DATABASE         = var.firestore_database
          GOOGLE_CLOUD_PROJECT       = var.project_id
          AUDIT_PUBSUB_SUBSCRIPTION  = local.subscription
          AUDIT_EVENT_DELIVERY       = "push"
          SETTLEMENT_API_URL         = var.settlement_api_url
          AUDIT_TENANT               = var.tenant
        })
        content {
          name  = env.key
          value = env.value
        }
      }
      dynamic "env" {
        for_each = var.gemini_api_key_secret == "" ? [] : [var.gemini_api_key_secret]
        content {
          name = "GEMINI_API_KEY"
          value_source {
            secret_key_ref {
              secret  = env.value
              version = "latest"
            }
          }
        }
      }
      startup_probe {
        http_get {
          path = "/health"
        }
      }
    }
  }
}

resource "google_cloud_run_v2_service_iam_member" "push" {
  name     = google_cloud_run_v2_service.app.name
  location = google_cloud_run_v2_service.app.location
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.push.email}"
}

resource "google_cloud_run_v2_service_iam_member" "invokers" {
  for_each = toset(var.invoker_members)
  name     = google_cloud_run_v2_service.app.name
  location = google_cloud_run_v2_service.app.location
  role     = "roles/run.invoker"
  member   = each.value
}

resource "google_secret_manager_secret_iam_member" "gemini_key" {
  count     = var.gemini_api_key_secret == "" ? 0 : 1
  secret_id = var.gemini_api_key_secret
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app.email}"
}

output "url" {
  value = google_cloud_run_v2_service.app.uri
}

output "service_account" {
  value = google_service_account.app.email
}
