# infra-self-service-api

A self-service API that provisions and configures infrastructure on request, using
Terraform and Ansible. It's developed locally against the [Floci](https://github.com/floci-io/floci)
AWS emulator; the same code will be deployed to real AWS through GitHub Actions.

> **Work in progress.** Built in phases; see the roadmap below.

## Roadmap

### Done
- [x] **Local foundation**: Floci emulator via Docker Compose, AWS CLI smoke test
- [x] **Terraform**: bootstrap, platform and per-environment layers with S3 state and lock files; one code base for Floci and real AWS
- [x] **Ansible**: configure the provisioned instances (idempotent playbooks and roles)
- [x] **API: request and status**: `POST /environments` (202 + job), `GET /environments/{id}`, RFC 9457 problem details, OpenAPI docs
- [x] **Job engine**: claims with `SKIP LOCKED`, leases with heartbeats and fencing tokens, recovery sweep, Terraform and Ansible run per job ([ADR 0001](docs/adr/0001-job-execution-model.md))

### Next
- [ ] **Recovery policy**: auto-retry jobs whose lease expired while planning; interrupt the rest
- [ ] **`DELETE /environments/{id}`**: destroy job, `409` while another job is active
- [ ] **Resume / retry** for interrupted and failed jobs
- [ ] **Audit outbox publisher**: `audit_events` to DynamoDB ([ADR 0003](docs/adr/0003-postgresql-source-of-truth-and-audit-outbox.md))
- [ ] **Tests**: integration tests against Floci end to end
- [ ] **CI**: build, tests, `terraform fmt/validate`, `ansible-lint` on GitHub Actions
- [ ] **Real AWS deploy**: GitHub OIDC (no stored keys), smallest instance types, billing alarm, then `terraform destroy`
- [ ] **README**: architecture diagram, design decisions, emulator vs real AWS

### Known gaps
- Interrupted jobs don't release a stale Terraform S3 lock yet (`force-unlock`)
- A hard-killed API host can leave an orphaned `terraform` process; run it in a container
- The shared provider cache isn't safe for concurrent first-time `terraform init`; a read-only provider mirror is planned for the container image
- S3 bucket hardening (versioning, encryption, public access block) before the first real AWS deploy

## Tech stack

Java 25 · Spring Boot 4.1 · PostgreSQL · AWS SDK for Java v2 · Terraform · Ansible · Docker · GitHub Actions · Floci

## Quick start (local, no AWS account needed)

Prerequisites: Docker, AWS CLI v2, Terraform >= 1.10, ansible-core, JDK 25 (Maven is not needed:
the `./mvnw` wrapper downloads it), Linux host (see [emulator notes](docs/emulator-notes.md)
for networking differences on Docker Desktop).

```bash
# 1. Start the emulator and check the AWS CLI can reach it
docker compose up -d
./scripts/smoke-test.sh

# 2. One-time: SSH key for the emulated EC2 instances (stays outside the repo)
ssh-keygen -t ed25519 -N '' -f ~/.ssh/infra-self-service-api

# 3. Terraform, layer by layer (scripts/tf.sh picks the backend for the target)
scripts/tf.sh bootstrap floci apply             # state bucket (once per target)
scripts/tf.sh platform floci apply              # shared VPC, subnet, bucket, DynamoDB, SSM
# One environment by hand (normally the API does this for each request):
export ENVIRONMENT_ID=$(uuidgen | tr A-F a-f)
scripts/tf.sh environment floci apply -var name=demo -var job_id=$(uuidgen | tr A-F a-f)

# 4. Configure that environment's instances with Ansible (see ansible/requirements.yml)
scripts/tf.sh environment floci output -json | python3 ansible/inventory/generate.py > ansible/inventory/generated.yml
cd ansible
ansible-galaxy collection install -r requirements.yml
ansible-playbook site.yml
cd ..

# 5. Run the API (JDK 25; adjust JAVA_HOME to your install)
export JAVA_HOME=~/.jdks/temurin-25
cd api
./mvnw verify                                   # build + tests (uses Testcontainers)
SPRING_PROFILES_ACTIVE=floci ./mvnw spring-boot:run
# http://localhost:8080/swagger-ui.html, http://localhost:8080/actuator/health

# 6. Request an environment: the job engine plans, applies and configures it in the background
curl -i -X POST localhost:8080/environments -H 'Content-Type: application/json' -d '{"name":"demo-api"}'
curl localhost:8080/environments/<id>          # PROVISIONING -> READY; currentJob shows the phase
# Per-job plan, inventory and step logs: /tmp/infra-ssa/jobs/<jobId>/attempt-<n>/ (infrassa.runner.work-dir)
```

Contributing: enable the pre-commit formatting check once per clone with
`git config core.hooksPath .githooks`. Fix formatting with `cd api && ./mvnw spotless:apply`.

## Repository layout

```
docker-compose.yml   Floci emulator and PostgreSQL (host port 15432)
api/                 Spring Boot 4.1 service (Java 25)
scripts/             Emulator env and smoke test; tf.sh runs Terraform per layer and target
terraform/
  bootstrap/         State bucket (local state, applied once per target)
  platform/          Shared VPC, subnet, artifacts bucket, DynamoDB; publishes IDs to SSM
  environment/       Root run once per environment, one state each (driven by the API)
  modules/           environment (SG, key pair, instances) and s3-bucket
ansible/             Playbook, roles (base, users, app), inventory generated from Terraform outputs
docs/                Notes, including Floci vs real AWS differences
docs/adr/            Architecture decision records
.githooks/           Git hooks (pre-commit formatting check)
```

## Emulator vs real AWS

An emulator is not AWS. Differences found so far, and what still needs verifying on real AWS,
are tracked in [docs/emulator-notes.md](docs/emulator-notes.md).
