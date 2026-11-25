# The ledger database: PostgreSQL on the Always Free e2-micro VM (hub design note GCP-01, D2).
#
# Free tier: one e2-micro in us-central1 / us-west1 / us-east1, a 30 GB standard disk, no external
# IP (none needed: Private Google Access reaches Artifact Registry, Secret Manager and Cloud
# Storage). Container-Optimized OS runs the postgres image mirrored into the landscape's Artifact
# Registry. The password is read from Secret Manager at boot (never in Terraform state); a daily
# pg_dump goes to a Cloud Storage bucket (5 GB free) kept 14 days.

resource "google_compute_network" "ledger" {
  name                    = "finance-landscape"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "ledger" {
  name                     = "finance-landscape-${var.region}"
  network                  = google_compute_network.ledger.id
  region                   = var.region
  ip_cidr_range            = "10.10.0.0/24"
  private_ip_google_access = true # Artifact Registry, Secret Manager, Cloud Storage without NAT
}

resource "google_compute_address" "ledger" {
  name         = "ledger-db"
  region       = var.region
  subnetwork   = google_compute_subnetwork.ledger.id
  address_type = "INTERNAL"
  address      = "10.10.0.10"
}

# Only the subnet (Cloud Run's Direct VPC egress uses its addresses) reaches PostgreSQL.
resource "google_compute_firewall" "postgres" {
  name          = "ledger-db-postgres"
  network       = google_compute_network.ledger.id
  direction     = "INGRESS"
  source_ranges = [google_compute_subnetwork.ledger.ip_cidr_range]
  target_tags   = ["ledger-db"]
  allow {
    protocol = "tcp"
    ports    = ["5432"]
  }
}

# Administration through IAP TCP forwarding (free), never a public SSH port.
resource "google_compute_firewall" "iap_ssh" {
  name          = "ledger-db-iap-ssh"
  network       = google_compute_network.ledger.id
  direction     = "INGRESS"
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["ledger-db"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_service_account" "ledger_vm" {
  account_id   = "ledger-db-vm"
  display_name = "ledger database VM"
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "settlement-db-password"
  labels    = local.labels
  replication {
    auto {}
  }
  # the value is added out of band at cutover (gcloud secrets versions add): never in state
}

resource "google_secret_manager_secret_iam_member" "vm_reads_password" {
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.ledger_vm.email}"
}

resource "google_storage_bucket" "backups" {
  name                        = "${var.project_id}-ledger-backups"
  location                    = upper(var.region)
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = true
  labels                      = local.labels
  lifecycle_rule {
    condition {
      age = 14
    }
    action {
      type = "Delete"
    }
  }
}

resource "google_storage_bucket_iam_member" "vm_writes_backups" {
  bucket = google_storage_bucket.backups.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.ledger_vm.email}"
}

resource "google_project_iam_member" "vm_pulls_images" {
  project = var.project_id
  role    = "roles/artifactregistry.reader"
  member  = "serviceAccount:${google_service_account.ledger_vm.email}"
}

resource "google_project_iam_member" "vm_writes_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.ledger_vm.email}"
}

resource "google_compute_instance" "ledger" {
  name                      = "ledger-db"
  machine_type              = "e2-micro" # Always Free
  zone                      = "${var.region}-a"
  tags                      = ["ledger-db"]
  labels                    = local.labels
  allow_stopping_for_update = true

  boot_disk {
    initialize_params {
      image = "cos-cloud/cos-stable"
      size  = 30            # GB; Always Free covers 30 GB of standard persistent disk
      type  = "pd-standard" # balanced disk is not free
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.ledger.id
    network_ip = google_compute_address.ledger.address
    # no access_config: no external IP
  }

  service_account {
    email  = google_service_account.ledger_vm.email
    scopes = ["cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot = true
  }

  metadata = {
    enable-oslogin = "TRUE"
    user-data = templatefile("${path.module}/ledger-cloud-init.yaml", {
      postgres_image  = var.postgres_image
      registry_host   = split("/", var.postgres_image)[0]
      password_secret = google_secret_manager_secret.db_password.id
      backup_bucket   = google_storage_bucket.backups.name
    })
  }
}
