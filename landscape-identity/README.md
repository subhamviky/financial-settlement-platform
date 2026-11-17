# landscape-identity

The Entra side of service-to-service identity across the Order-to-Cash landscape (hub design note SI-01). Entra ID is the single workload issuer. Each service proves who it is with its own cloud's identity, exchanged for an Entra token without a secret. Each receiver checks the roles in that token.

| Kind | Who | What this stack creates |
|---|---|---|
| Receiver | settlement-service | An app with roles `settlement.submit`, `settlement.adjust`, `settlement.read` and `tenant:<id>` per tenant; v2 tokens; assignment required |
| Receiver | reconciliation-service (service route) | An app with `reconciliation.read` and `tenant:<id>` |
| Caller | o2c-agent-service (Google Cloud) | An app with a federated credential: issuer `https://accounts.google.com`, subject = the Cloud Run service account's unique id |
| Caller | reconciliation-service (AWS) | An app with a federated credential: the account's outbound identity federation issuer and the role's subject |
| Caller | audit-ai-service (Azure) | Nothing to create (its managed identity is already an Entra principal); only grants |

## Grants

| Caller | settlement-service | reconciliation-service |
|---|---|---|
| o2c-agent-service | submit, read | read |
| reconciliation-service | adjust, read | — |
| audit-ai-service | read | — |

Every caller is also granted `tenant:<id>` for each tenant in `tenants`.

## Wiring

| Input here | From |
|---|---|
| `o2c_google_service_account_id` | the unique id of o2c's runtime service account (`gcloud iam service-accounts describe … --format='value(uniqueId)'`) |
| `recon_aws_issuer`, `recon_aws_subject` | `aws iam enable-outbound-web-identity-federation` (once per account), then `aws iam get-outbound-web-identity-federation-info`; the subject is the `sub` claim of the role's token |
| `audit_ai_principal_id` | audit-ai's output `identity_principal_id` |

| Output | To |
|---|---|
| `settlement_app_client_id`, `issuer` | settlement-service's Container Apps authentication (`settlement-service/infra/identity.tf`) |
| `settlement_scope` | callers' `E2A_WORKLOAD_TOKEN_SCOPES` (audit-ai's `settlement_scope`; o2c and reconciliation stacks) |
| `reconciliation_audience`, `reconciliation_scope`, `issuer` | reconciliation's service-route authorizer and o2c's scopes |
| `o2c_client_id`, `recon_client_id` | each caller's `E2A_WORKLOAD_ENTRA_CLIENT_ID` |

Tested with a mocked `azuread` provider (`terraform test`, in CI). The first real deployment should check each call path once.
