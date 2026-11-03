# infra-self-service-api

A self-service API that provisions and configures infrastructure on request, using
Terraform and Ansible. It's developed locally against the [Floci](https://github.com/floci-io/floci)
AWS emulator and deployed to real AWS through GitHub Actions.

> **Work in progress.** Built in phases; see status below.

## Status

- [x] **Local foundation**: Floci emulator via Docker Compose, AWS CLI smoke test
- [x] **Terraform**: VPC, EC2, S3, DynamoDB; one code base switching between Floci and real AWS
- [ ] **Ansible**: configure the provisioned instances (idempotent playbooks and roles)
- [ ] **Spring Boot API**: `POST/GET/DELETE /environments`, async provisioning jobs, status tracking
- [ ] **Tests**: unit tests and integration tests against Floci
- [ ] **CI**: build, tests, `terraform validate`, `ansible-lint` on GitHub Actions
- [ ] **Real AWS deploy**: GitHub OIDC (no stored keys), smallest instance types, then `terraform destroy`

## Tech stack

Java 21 · Spring Boot 3 · AWS SDK for Java v2 · Terraform · Ansible · Docker · GitHub Actions · Floci

## Quick start (local, no AWS account needed)

Prerequisites: Docker, AWS CLI v2, Terraform >= 1.9, Linux host (see
[emulator notes](docs/emulator-notes.md) for networking differences on Docker Desktop).

```bash
# 1. Start the emulator and check the AWS CLI can reach it
docker compose up -d
./scripts/smoke-test.sh

# 2. One-time: SSH key for the emulated EC2 instances (stays outside the repo)
ssh-keygen -t ed25519 -N '' -f ~/.ssh/infra-self-service-api

# 3. Provision VPC, EC2, S3 and DynamoDB on the emulator
cd terraform
terraform init
terraform workspace select -or-create floci
terraform apply
```

## Repository layout

```
docker-compose.yml   Floci emulator
scripts/             Env setup and smoke test for the emulator
terraform/           IaC; target = "floci" | "aws", one workspace per target
docs/                Notes, including Floci vs real AWS differences
```

## Emulator vs real AWS

An emulator is not AWS. Differences found so far, and what still needs verifying on real AWS,
are tracked in [docs/emulator-notes.md](docs/emulator-notes.md).
