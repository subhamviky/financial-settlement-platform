# Changelog

Newest first. Format: [Keep a Changelog](https://keepachangelog.com). Released by commit and Cloud Run revision.

## 28 November 2026
### Added
- Engineering records (`docs/engineering/`): ADRs, post-incident review, threat model, data classification, operations and test strategy, API reference, product documents.

## 27 November 2026 (GCP-02 Phase B)
### Added
- Correlation id: one JSON log line per request carrying `X-Correlation-Id`, in both services.
### Changed
- Google Cloud profile: database connection timeout 5 s, so a request fails fast when the ledger database is down (drill 1 found 30 s hangs).

## 25 November 2026 (GCP-01)
### Added
- Google Cloud free-tier deployment: PostgreSQL on an Always Free VM with daily backups, Pub/Sub events with push delivery to the audit service, Firestore audit state, Cloud Run IAM identity.

## Earlier
- The completed project rebuilt from the architectural spike on the E2A and A2C frameworks; see the README.
