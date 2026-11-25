# settlement-service on Google Cloud, free tier only (hub design note GCP-01).
#
# Cloud Run (scale to zero, one instance) with Direct VPC egress to the ledger database on the
# Always Free e2-micro VM (ledger_vm.tf); events to the settlement-events Pub/Sub topic (the
# outbox relay); one Cloud Scheduler job for the outbox and stuck-settlement recovery (Cloud Run
# runs no timers between requests). Project-wide pieces (APIs, Artifact Registry, Workload
# Identity Federation) come from the landscape foundation. Azure stays in ../. No secrets here.

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

locals {
  name   = "settlement-service"
  labels = { app = "settlement-service", landscape = "finance-ai", managed-by = "terraform" }
}

resource "google_service_account" "app" {
  account_id   = "settlement-app"
  display_name = "settlement-service runtime"
}

resource "google_service_account" "scheduler" {
  account_id   = "settlement-scheduler"
  display_name = "Cloud Scheduler job of settlement-service"
}

resource "google_secret_manager_secret_iam_member" "app_reads_password" {
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app.email}"
}

# ------------------------------------------------------------------ events
resource "google_pubsub_topic" "events" {
  name                       = var.events_topic
  labels                     = local.labels
  message_retention_duration = "86400s"
}

resource "google_pubsub_topic_iam_member" "app_publishes" {
  topic  = google_pubsub_topic.events.id
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.app.email}"
}

# ------------------------------------------------------------------ Cloud Run
resource "google_cloud_run_v2_service" "app" {
  name                = local.name
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL" # callers are other Cloud Run services and the scheduler; IAM decides
  deletion_protection = false
  labels              = local.labels

  template {
    service_account = google_service_account.app.email
    timeout         = "60s"
    scaling {
      min_instance_count = 0 # scale to zero: Always Free
      max_instance_count = var.max_instance_count
    }
    vpc_access {
      network_interfaces {
        network    = google_compute_network.ledger.id
        subnetwork = google_compute_subnetwork.ledger.id
      }
      egress = "PRIVATE_RANGES_ONLY" # only the ledger database goes through the VPC
    }
    containers {
      image = var.image
      ports {
        container_port = 8080
      }
      resources {
        cpu_idle          = true # CPU only while a request is served
        startup_cpu_boost = true # Spring Boot starts faster from zero
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }
      dynamic "env" {
        for_each = {
          SPRING_PROFILES_ACTIVE         = "prod,gcp"
          DB_URL                         = "jdbc:postgresql://${google_compute_address.ledger.address}:5432/settlement"
          DB_USER                        = "settlement"
          GOOGLE_CLOUD_PROJECT           = var.project_id
          SETTLEMENT_EVENTS_PUBSUB_TOPIC = google_pubsub_topic.events.name
        }
        content {
          name  = env.key
          value = env.value
        }
      }
      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
      startup_probe {
        http_get {
          path = "/actuator/health"
        }
        initial_delay_seconds = 5
        period_seconds        = 5
        failure_threshold     = 24 # up to two minutes for a cold Spring Boot start
      }
    }
  }
}

resource "google_cloud_run_v2_service_iam_member" "scheduler" {
  name     = google_cloud_run_v2_service.app.name
  location = google_cloud_run_v2_service.app.location
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.scheduler.email}"
}

resource "google_cloud_run_v2_service_iam_member" "invokers" {
  for_each = toset(var.invoker_members)
  name     = google_cloud_run_v2_service.app.name
  location = google_cloud_run_v2_service.app.location
  role     = "roles/run.invoker"
  member   = each.value
}

# ------------------------------------------------------------------ Cloud Scheduler (1 of the 3 free jobs)
resource "google_cloud_scheduler_job" "maintenance" {
  name      = "settlement-maintenance"
  region    = var.region
  schedule  = var.schedule
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.app.uri}/internal/jobs/maintenance"
    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = google_cloud_run_v2_service.app.uri
    }
  }

  retry_config {
    retry_count = 1
  }
}

output "url" {
  value = google_cloud_run_v2_service.app.uri
}

output "service_account" {
  value = google_service_account.app.email
}

output "events_topic" {
  value = google_pubsub_topic.events.name
}

output "ledger_db_address" {
  value = google_compute_address.ledger.address
}

output "backup_bucket" {
  value = google_storage_bucket.backups.name
}
