# ADR 0002: Terraform layout and state ownership

- **Status:** Accepted
- **Date:** 2026-11-04

## Context

Phase 2 used a single Terraform root module for everything: network, bucket, DynamoDB table and
instances. The API now creates and destroys environments on request, so each environment needs
its own lifecycle and its own state. Shared infrastructure must not be changed as a side effect
of an API request.

## Decision

Three layers, each with its own state:

| Layer | Contents | State | Changed by |
|---|---|---|---|
| `terraform/bootstrap/` | S3 bucket for Terraform state (versioned, encrypted, public access blocked) | Local, applied once per target | A human, once |
| `terraform/platform/` | Shared VPC, subnet, artifacts bucket, DynamoDB table, RDS (real AWS) | S3 backend | **CI only** |
| `terraform/modules/environment/` | Per-request stack: security group, key pair, instances | S3 backend, one key per environment (`environments/<id>/terraform.tfstate`) | **The API only** |

- The API generates a small root module per environment that calls `modules/environment` and
  reads the platform values it needs (VPC, subnet). It never plans or applies `platform/`.
- Environments must not read the platform *state* (`terraform_remote_state`): that would give the
  API read access to everything in it, including secrets. The platform publishes only the values
  environments need (e.g. SSM parameters), or environments look them up with `data` sources.
  The exact mechanism is decided when `platform/` is implemented. RDS uses
  `manage_master_user_password = true`, so its password lives in Secrets Manager, not in state.
- Locking uses the S3 backend's native lock file (`use_lockfile = true`, Terraform >= 1.10),
  so no DynamoDB lock table is needed.
- Every environment resource is tagged with `EnvironmentId` and `JobId` (see ADR 0001,
  orphaned resources).

## Consequences

- An API bug can at worst damage one environment's state, never the shared network.
- The API's AWS permissions can be limited to environment resources and the
  `environments/` prefix of the state bucket. Platform changes need CI's separate role.
- Bootstrap state is local by necessity (the bucket that would hold it doesn't exist yet). It is
  small and rarely changes; it is not committed.
- Floci's S3 must support the conditional writes that `use_lockfile` relies on. To be verified
  when the backend is set up; recorded in `docs/emulator-notes.md` either way.

## Alternatives considered

- **One root module, workspace per environment:** workspace selection is stored in one shared
  file (`.terraform/environment`), so concurrent jobs in the same directory race. Rejected.
- **Platform and environments in one state:** every request would plan the shared network, and a
  bad request could change it. Rejected.
