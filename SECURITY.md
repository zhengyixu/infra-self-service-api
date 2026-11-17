# Security policy

This is a personal portfolio project, built and maintained by one person. It is not a
product and has no production deployment.

## Supported versions

Only the `main` branch is supported. There are no releases; fixes land on `main`.

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub private vulnerability reporting:
[open a draft security advisory](https://github.com/zhengyixu/infra-self-service-api/security/advisories/new).

Do not open a public issue for a vulnerability. Helpful details: the affected file or
endpoint, steps to reproduce, and the impact you expect.

## What to expect

Responses are best effort. I aim to acknowledge a report within a week or two and to
keep you updated in the advisory, but I cannot promise a response time or a fix date.
Credit is given in the advisory unless you prefer otherwise.

## Scope

In scope:

- The REST API in `api/` (request handling, validation, error responses)
- Process execution: how the API runs `terraform` and `ansible-playbook` as child processes
- The Terraform modules and roots under `terraform/`
- The job store (PostgreSQL schema and job/lease handling)

Out of scope: vulnerabilities in third-party dependencies with no project-specific impact
(Dependabot tracks those), and the Floci emulator itself.

## Dummy credentials

The following contain credentials for the local AWS emulator and the local PostgreSQL
container only. They are dummy values (`test`/`test` for the emulator), not secrets, and
grant no access to any real system:

- `scripts/floci-env.sh`
- `terraform/*/floci.s3.tfbackend` and the Floci branch of `terraform/*/providers.tf`
- `api/src/main/resources/application-floci.yml`
- `docker-compose.yml`

Real AWS access is planned through GitHub OIDC, without stored keys.

## Known limitation: no authentication

The API has no authentication or authorization yet. Anyone who can reach it can request
environments, which provisions infrastructure. Run it only on localhost or a trusted
network, and never expose it to untrusted networks. Other known issues are listed in the
[README](README.md#security).
