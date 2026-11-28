# Release process: settlement-service and audit-ai-service

The platform's release workflows (`promote`, `ops`, `landscape-gcp`) live in the [assistant repository](https://github.com/subhamviky/enterprise-finance-genai-assistant/blob/main/docs/engineering/operations/release-process.md). For this repository:

1. **Branch and pull request.** CI (`settlement-ci`) builds and tests settlement-service (Java 21, H2 in PostgreSQL mode; Service Bus and Pub/Sub publishers on emulators); runs the Python audit service's tests with the state in Cosmos DB and in Firestore emulators; strict E2A conformance; the release gate; checks that the events the tests produce satisfy the JSON-Schema contract; and Terraform fmt, validate and test with mock providers.
2. **Merge to `main`.** There is no packaged version: a release is a commit and a Cloud Run revision.
3. **Deploy.** Actions, `ops` (in the assistant repository), `deploy`, service `settlement-service` or `audit-ai-service`, ref `main`. The workflow builds the image from this repository (Maven inside the Docker build for settlement, the longest build) and gives the service a new revision at 100%. There is no canary for these services; the assistant's `promote` gates the assistant only.
4. **Verify.** Run `landscape-gcp` on `main`: all 14 steps, including the balanced ledger (step 8) and the replay checks.
5. **Rollback.** `gcloud run services update-traffic <service> --region us-central1 --to-revisions <previous>=100`. A schema change to the ledger needs a forward fix: no migration here is reversible by traffic alone.

The settlement contract (`contracts/`) is consumed by reconciliation (a vendored copy checked in its CI against this repository's `main`): a breaking change to an event needs a new contract version and a coordinated change.
