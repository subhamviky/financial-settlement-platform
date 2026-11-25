# Plans infra/gcp against a mocked google provider: no project, no credentials (GCP-01).
mock_provider "google" {
  mock_data "google_project" {
    defaults = {
      number = "123456789012"
    }
  }
}

variables {
  project_id = "demo-project"
}

run "push_delivery_on_the_free_tier" {
  command = plan

  assert {
    condition     = google_cloud_run_v2_service.app.template[0].scaling[0].min_instance_count == 0 && google_cloud_run_v2_service.app.template[0].containers[0].resources[0].cpu_idle == true
    error_message = "Scale to zero, CPU only during requests."
  }

  assert {
    condition     = contains([for e in google_cloud_run_v2_service.app.template[0].containers[0].env : "${e.name}=${e.value != null ? e.value : ""}"], "AUDIT_EVENT_DELIVERY=push") && contains([for e in google_cloud_run_v2_service.app.template[0].containers[0].env : "${e.name}=${e.value != null ? e.value : ""}"], "AUDIT_STATE_BACKEND=firestore")
    error_message = "Events are pushed (no background worker); state is in Firestore."
  }

  assert {
    condition     = google_pubsub_subscription.events.topic == "projects/demo-project/topics/settlement-events" && length(google_pubsub_subscription.events.push_config[0].oidc_token) == 1
    error_message = "The subscription is on settlement-service's topic and pushes with an OIDC token."
  }

  assert {
    condition     = google_pubsub_subscription.events.dead_letter_policy[0].max_delivery_attempts == 5
    error_message = "Events are dead-lettered after 5 attempts."
  }
}

run "never_public" {
  command = plan

  variables {
    invoker_members = ["allAuthenticatedUsers"]
  }

  expect_failures = [var.invoker_members]
}
