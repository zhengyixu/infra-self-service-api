# Platform roadmap

This document plans how the project grows from a self-service provisioning API into a small
but complete cloud platform: built with Infrastructure as Code, released through a pipeline
with security gates, deployed safely, observable, and able to survive the loss of a region.

It serves two readers. A visitor can see what the project sets out to prove and how far it
has got. A contributor, human or AI agent, can pick up a track and know what to build, why,
and what counts as finished.

The README roadmap covers the core service. This document covers what comes after it.

## What the project should prove

Senior cloud and DevOps roles on regulated platforms ask for a recurring set of skills. Each
track below turns one of them into something that runs and can be inspected.

| Skill the role asks for | Track |
|---|---|
| CI/CD pipelines with validation, scanning and approval gates | A, B |
| Terraform: modules, state management, repeatable deployments | Core, B, G |
| AWS networking, load balancing and container platforms | C |
| Blue/green deployment and progressive traffic shifting | D |
| Monitoring, troubleshooting, runbooks | E |
| Security controls, vulnerability remediation, auditability | A, F |
| Multiple environments (Dev, SIT, Prod) with controlled promotion | G |
| Multi-region resilience and disaster recovery | H |
| Cost awareness | B, I |

## Ground rules

These apply to every track.

1. **Emulator first, real AWS second.** Build and test against the local emulator (Floci)
   where it can prove the behaviour. No emulator can prove IAM enforcement, real networking,
   load balancers, managed containers or anything across regions, so those are verified on
   real AWS. Record every difference found in [emulator-notes.md](emulator-notes.md), which
   also says why Floci was chosen over AWS Emulator.
2. **Real AWS costs money and is never left running.** Every real deployment follows the
   checklist in [Working on real AWS](#working-on-real-aws). A track that needs a paid
   resource says so, and the owner approves it before anything is created.
3. **Everything goes through code and the pipeline.** No console changes, no long-lived
   access keys, no secrets in the repository.
4. **A track is finished when there is evidence.** Each track has a "done when" list.
   Evidence goes in `docs/evidence/<track>/`: a pipeline run link, a command transcript, a
   drill timeline, a screenshot where nothing else will do. Say where each item was verified:
   on the emulator or on real AWS, and on which date.
5. **The README stays honest.** Move an item to "Done" only when its evidence exists. This
   repository is cited as proof of skill, so a claim that the code does not support does
   harm.
6. **Decisions with real alternatives get an ADR** in `docs/adr/`, including the options that
   were rejected and what they would have cost.
7. **Prefer the smallest thing that proves the skill.** One service, two availability zones,
   two regions and the cheapest instance sizes are enough. This is a demonstration, not a
   production estate.

## Order of work

```
Core  ->  A  ->  B  ->  C  ->  D  ->  H
                 |      |
                 |      +->  E  ->  D
                 +->  F, G, I  (after B, in any order)
```

A comes before B so that the gates exist before anything touches a real account. C comes
before D and H because there must be a running service to shift traffic to and to fail over.
E starts alongside C, and its health alarms must exist before D can finish: D's automatic
rollback is triggered by them. H is last: it costs the most and depends on almost everything
else.

Each track that creates paid resources states its expected cost before it starts, as part of
the approval in [Working on real AWS](#working-on-real-aws).

## Core: finish the service

The items under "Next" and "Known gaps" in the README. Three of them block later tracks:

- `DELETE /environments/{id}` and the destroy job. Without it, a real deployment cannot
  clean up the environments it creates.
- Integration tests against Floci. Track A runs them in CI.
- Release of a stale Terraform lock after an interrupted job. Track E writes the runbook
  for it.

## Track A: a pipeline with validation and security gates

Needs no AWS account. Start here.

**Build**
- A GitHub Actions workflow for pull requests and for `main`: Maven build and tests,
  integration tests with Floci as a service container, `terraform fmt -check` and
  `terraform validate` for each layer, `tflint`, `ansible-lint`.
- Security scanning as gates: a secret scanner (gitleaks) and Trivy, which covers the
  Terraform (IaC misconfiguration), the Maven dependencies and, from Track C on, the container
  image. One scanner for all three means one place for exceptions.
- Supply chain: third-party GitHub Actions pinned to a full commit SHA rather than a tag, and
  workflow `permissions` set to the minimum each job needs.
- Branch protection on `main` so that a red check blocks the merge.
- A remediation record. When a scanner reports a finding, either fix it or add a documented
  exception that names the rule, the reason and a review date. Keep the exceptions in one
  file so an auditor could read them in a minute.

**Done when**
- The workflow is green on `main` and the README shows its badge.
- A pull request that introduces a known-bad change, such as a security group open to the
  world on port 22 or a committed fake key, is blocked by the gate. Keep the link to that
  run as evidence.
- Every scanner finding is either fixed or listed as an exception with a reason.

## Track B: first real AWS deployment, with guardrails

**Build**
- A budget and a billing alarm, created before any other resource.
- GitHub OIDC federation to AWS. Two roles: one that can only plan, used on pull requests,
  and one that can apply, used on `main`. Neither is broader than the platform layer needs.
  The plan role is read-only except for one thing: `plan` takes the state lock, which with
  `use_lockfile` means writing `<key>.tflock` in the state bucket. Grant `PutObject` and
  `DeleteObject` on `*.tflock` only, rather than running plan with `-lock=false`.
- Hardening of the state bucket: versioning, encryption, public access block. This is listed
  as a known gap in the README.
- The pipeline for the platform layer. On a pull request: `plan`, summarised in the pull
  request for code review. On `main`: `plan -out` again, because a plan saved on the pull
  request is stale once `main` has moved and Terraform refuses to apply a stale plan. That
  plan is saved as an artifact and shown at a GitHub Environment approval gate, and `apply`
  runs exactly that saved plan once it is approved.
- A destroy workflow, also behind approval.
- A teardown check: a script that lists every resource carrying the project's tags (Resource
  Groups Tagging API, in each region used) and fails if it finds any. Every real AWS session
  ends with it, starting with this track's.

**Done when**
- The platform layer has been applied to real AWS by the pipeline and destroyed again, with
  no access key stored anywhere.
- The apply step ran the plan that was approved at the gate, not a new one.
- The teardown check found nothing after the destroy.
- The open items in emulator-notes.md that this deployment can settle are settled: IAM
  scoping of the SSM path, security group enforcement, S3 lock behaviour.
- The cost of the run is recorded.

## Track C: the API in containers, behind a load balancer

**Build**
- A Dockerfile for the API: multi-stage build, non-root user, and the `terraform` and
  `ansible` binaries it runs as child processes. The README lists two known gaps that the
  image changes:
  - The provider cache that is unsafe for concurrent `terraform init` is closed by baking a
    read-only provider mirror into the image.
  - The orphaned `terraform` process after a hard kill is not closed but turned into a
    different gap: when the task dies, its child processes die with it, so nothing keeps
    running, but an apply is cut off halfway. That is the INTERRUPTED job with a stale state
    lock, which the recovery sweep and the lock release from Core must handle. Say so in
    the README instead of listing the gap as closed.
- An image registry (ECR) with scan-on-push and immutable tags, and an image scan in the
  pipeline that blocks a release with serious findings.
- A network that spans two availability zones, with the service in private subnets behind an
  Application Load Balancer.
- The service on ECS Fargate, with a task role that has only the permissions the job engine
  needs. The database is the smallest RDS PostgreSQL instance, with its password managed by
  Secrets Manager.

**Decide first, in an ADR**
- ECS or EKS. ECS is the default here because it has no control-plane fee and less to run.
  EKS can be a later, optional track.
- How private subnets reach AWS APIs and the internet. A NAT gateway is billed by the hour.
  VPC endpoints or a short-lived NAT may be cheaper for a demonstration. Check current
  prices and write the comparison down.
- How Ansible reaches the instances it configures. Locally it uses SSH to the container IP;
  from a Fargate task in a private subnet the instances must be reachable, and the SSH
  private key needs a home (Secrets Manager, read by the task role). The alternative is
  Ansible's `aws_ssm` connection through Session Manager: no inbound port 22, no key to
  store, access controlled by IAM, but it needs the SSM agent and an instance profile on
  every instance, an S3 bucket for file transfer, and it cannot be tested on the emulator.

**Done when**
- The pipeline builds, scans, pushes and deploys the image, and the API answers through the
  load balancer.
- A request to `POST /environments` on real AWS provisions an environment end to end and
  `DELETE` removes it.
- Stopping one task, or one availability zone's tasks, does not interrupt the service.

## Track D: safe deployments

**Build**
- Blue/green deployment of the ECS service with progressive traffic shifting, for example a
  canary step followed by the rest. There are three ways to do it: CodeDeploy, the blue/green
  deployment built into ECS itself (with lifecycle hooks, added in 2025), and weighted
  target groups managed by Terraform. Check the current documentation for each and record
  the choice in an ADR.
- Automatic rollback when a health alarm fires during the shift. The alarms come from
  Track E, so E must be far enough along before this track can finish.
- A rule for database changes during a shift: every migration must work with both the old
  and the new version of the service.

**Done when**
- A normal release shifts traffic in steps and completes without failed requests. Measure
  that with a load test during the release.
- A deliberately broken release is rolled back automatically. Keep the timeline as evidence.
- The runbook for a manual rollback exists and has been followed once.

## Track E: observability and runbooks

**Build**
- Structured logs in CloudWatch with a retention period set in code.
- Metrics for what matters to this service: jobs by phase and outcome, job duration, lease
  expiries, queue depth. Alarms on the ones that mean someone must act.
- A dashboard defined in Terraform.
- Runbooks in `docs/runbooks/`: a stuck or interrupted job, a stale Terraform lock, a failed
  deployment, a region failover. Each one states the symptom, how to confirm it, the steps,
  and how to check that the fix worked.

**Done when**
- Each alarm has been made to fire on purpose and its runbook followed.
- A reader can go from an alarm to the cause using only the dashboard, the logs and the
  runbook.

## Track F: security and compliance controls

**Build**
- Encryption with KMS for storage, the database, volumes and logs.
- A tagging standard, applied through provider default tags and checked in the pipeline.
- CloudTrail, and AWS Config with a small set of managed rules, so that a change made outside
  the pipeline is visible. Cost: Config bills for every configuration item it records and
  every rule evaluation, so record only the resource types the rules need and turn the
  recorder off after the demonstration. The first CloudTrail trail for management events is
  free; data events and extra trails are not.
- The audit publisher from ADR 0003: every status change of an environment or a job reaches
  a durable audit store.
- A review of each IAM role against what it actually used, tightened where it was broader.

**Done when**
- A change made by hand in the console is detected and reported.
- The audit trail for one environment can answer who requested it, when, what was applied
  and by which job.
- No role has a wildcard action or resource that the review could not justify.

## Track G: several environments and controlled promotion

**Build**
- Dev, SIT and Prod as separate deployments of the same code, each with its own variables
  and state. A single account with name and state separation is acceptable here. Say plainly
  in the README that organisations normally use separate accounts, and why.
- Cost: three environments roughly triple what Tracks C and D run (load balancer, tasks,
  database, NAT or endpoints). Keep SIT and Prod up only for the promotion run, or scale
  them to zero between runs, and state the estimate before starting.
- Promotion through the pipeline: a change reaches SIT only after Dev is green, and Prod only
  after an approval.
- Versioned Terraform modules, so that an environment can pin the version it runs.
- Scheduled drift detection: a plan that runs daily and reports when the real infrastructure
  no longer matches the code.

**Done when**
- One change has been promoted through all three environments by the pipeline.
- A drift introduced by hand is reported by the scheduled plan.

## Track H: multi-region resilience

The emulator cannot show any of this. It is the most expensive track, so plan it in full and
get it approved before creating anything.

**Build**
- A second region that can run the service. Choose and justify a strategy in an ADR: backup
  and restore, pilot light, warm standby or active-active, with the recovery time and
  recovery point each one gives and what each one costs.
- Data in the second region: a database replica or copied snapshots, replicated artifacts,
  and a decision about where Terraform state lives when a region is lost.
- Traffic routing between regions with health checks. Route 53 failover records are the
  usual way. They need a hosted zone (billed monthly), a registered domain (billed yearly)
  and health checks (billed monthly each); include all three in the cost estimate. The
  second region also roughly doubles whatever runs in it for the length of the drill.

**Done when**
- A failover drill has been run: the primary region's service is taken down, traffic moves,
  and the service answers from the second region. The drill record states the measured
  recovery time and how much data, if any, was lost, against the targets set in the ADR.
- Failing back has been done and documented.

## Track I: cost

**Build**
- Cost allocation tags and a budget for each environment.
- A cost estimate on pull requests that change infrastructure, if a free tool allows it.
- The teardown check from Track B, extended to resources that the tagging API misses or that
  are created outside Terraform (log groups created by services, snapshots, ENIs left by a
  deleted task), so that nothing is left behind after a destroy.

**Done when**
- The README states what one full demonstration run costs, from measured figures.
- The teardown check has caught a leftover resource at least once, or has been tested with
  one planted on purpose.

## Working on real AWS

Follow this for every session that creates real resources.

1. State what will be created, the expected cost and for how long. Get the owner's approval.
2. Confirm that the budget and the billing alarm exist.
3. Deploy through the pipeline. If a step must be run by hand, say why and record the
   commands.
4. Collect the evidence the track asks for while the resources exist.
5. Destroy, then run the teardown check and confirm that it finds nothing.
6. Record the date, the duration and the cost in `docs/evidence/`.

## Status

Update this table when a track's evidence is complete.

| Track | Status | Verified on | Evidence |
|---|---|---|---|
| Core | In progress | Emulator | README roadmap |
| A. Pipeline with gates | Not started | | |
| B. First real AWS deployment | Not started | | |
| C. Containers and load balancer | Not started | | |
| D. Safe deployments | Not started | | |
| E. Observability and runbooks | Not started | | |
| F. Security and compliance | Not started | | |
| G. Environments and promotion | Not started | | |
| H. Multi-region resilience | Not started | | |
| I. Cost | Not started | | |
