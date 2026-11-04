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
| `terraform/environment/` (root) + `terraform/modules/environment/` | Per-request stack: security group, key pair, instances | S3 backend, one key per environment (`environments/<id>/terraform.tfstate`) | **The API only** |

- One committed root, `terraform/environment/`, serves every environment (see the amendment
  below). It calls `modules/environment` and reads the platform values it needs (VPC, subnet).
  The API never plans or applies `platform/`.
- Environments must not read the platform *state* (`terraform_remote_state`): that would give the
  API read access to everything in it, including secrets. The platform publishes only the values
  environments need as SSM `String` parameters under `/<project>/<target>/platform/`
  (`vpc-id`, `subnet-id`, `artifacts-bucket`), and the API's role can read that path only. RDS uses
  `manage_master_user_password = true`, so its password lives in Secrets Manager, not in state.
- Locking uses the S3 backend's native lock file (`use_lockfile = true`, Terraform >= 1.10),
  so no DynamoDB lock table is needed.
- Every environment resource is tagged with `EnvironmentId` and `JobId` (see ADR 0001,
  orphaned resources).

- Backend settings cannot use variables, so each layer has a `<target>.s3.tfbackend` file
  (`aws.s3.tfbackend` stays local). `scripts/tf.sh <layer> <target>` always pairs the backend
  with the same `target` and keeps each target's backend in its own data directory
  (`.terraform/<target>`). Every root also checks that `target` matches the account the provider
  is connected to (Floci reports `000000000000`).

## Amendment (2026-11-04): one shared environment root instead of generated roots

The original decision had the API generate a small root module per environment. Implemented
instead: a single committed root, `terraform/environment/`, run once per environment with
`-backend-config=key=environments/<id>/terraform.tfstate` and its own data directory
(`TF_DATA_DIR=.terraform/<target>-<id>`), so concurrent jobs never share initialized backend
state. Reasons:

- The code the API runs is the code CI checks (`fmt`, `validate`, lint); generated code is only
  checked once it exists.
- No code generation or template engine in the API.
- The concurrency problem that ruled out workspaces (one shared `.terraform/environment` file)
  does not apply: every environment has its own data directory.

Trade-off: changing `terraform/environment/` changes every environment on its next job.
That is the intended behavior for a platform, but it means reviewing that root like an API change.

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
