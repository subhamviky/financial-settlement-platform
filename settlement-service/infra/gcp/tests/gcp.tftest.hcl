# Plans infra/gcp against a mocked google provider: no project, no credentials (GCP-01).
mock_provider "google" {}

variables {
  project_id      = "demo-project"
  invoker_members = ["serviceAccount:o2c-app@demo-project.iam.gserviceaccount.com"]
}

run "free_tier_ledger_and_service" {
  command = plan

  assert {
    condition     = google_compute_instance.ledger.machine_type == "e2-micro" && google_compute_instance.ledger.boot_disk[0].initialize_params[0].type == "pd-standard" && google_compute_instance.ledger.boot_disk[0].initialize_params[0].size <= 30
    error_message = "The ledger runs on the Always Free e2-micro with at most 30 GB of standard disk."
  }

  assert {
    condition     = length(google_compute_instance.ledger.network_interface[0].access_config) == 0
    error_message = "The ledger VM has no external IP (none is free, none is needed)."
  }

  assert {
    condition     = google_compute_subnetwork.ledger.private_ip_google_access == true
    error_message = "Private Google Access reaches Artifact Registry, Secret Manager and Cloud Storage without NAT."
  }

  assert {
    condition     = contains(google_compute_firewall.postgres.source_ranges, "10.10.0.0/24") && length(google_compute_firewall.postgres.source_ranges) == 1 && anytrue([for a in google_compute_firewall.postgres.allow : contains(a.ports, "5432")])
    error_message = "Only the subnet reaches PostgreSQL."
  }

  assert {
    condition     = google_cloud_run_v2_service.app.template[0].scaling[0].min_instance_count == 0 && google_cloud_run_v2_service.app.template[0].vpc_access[0].egress == "PRIVATE_RANGES_ONLY"
    error_message = "Scale to zero; only the database traffic goes through the VPC (Direct VPC egress, no connector)."
  }

  assert {
    condition     = length([for e in google_cloud_run_v2_service.app.template[0].containers[0].env : e if e.name == "DB_PASSWORD" && e.value_source[0].secret_key_ref[0].secret == "settlement-db-password"]) == 1
    error_message = "The database password comes from Secret Manager."
  }

  assert {
    condition     = contains([for e in google_cloud_run_v2_service.app.template[0].containers[0].env : "${e.name}=${e.value != null ? e.value : ""}"], "SPRING_PROFILES_ACTIVE=prod,gcp") && contains([for e in google_cloud_run_v2_service.app.template[0].containers[0].env : "${e.name}=${e.value != null ? e.value : ""}"], "DB_URL=jdbc:postgresql://10.10.0.10:5432/settlement")
    error_message = "The gcp profile, and the ledger at its fixed internal address."
  }

  assert {
    condition     = google_cloud_scheduler_job.maintenance.http_target[0].http_method == "POST" && length(google_cloud_scheduler_job.maintenance.http_target[0].oidc_token) == 1
    error_message = "One scheduler job calls the maintenance endpoint with its own identity."
  }

  assert {
    condition     = google_storage_bucket.backups.public_access_prevention == "enforced" && anytrue([for r in google_storage_bucket.backups.lifecycle_rule : anytrue([for c in r.condition : c.age == 14])])
    error_message = "Backups are private and kept 14 days."
  }

}

run "never_public" {
  command = plan

  variables {
    invoker_members = ["allUsers"]
  }

  expect_failures = [var.invoker_members]
}

run "only_free_regions" {
  command = plan

  variables {
    region = "asia-south1"
  }

  expect_failures = [var.region]
}
