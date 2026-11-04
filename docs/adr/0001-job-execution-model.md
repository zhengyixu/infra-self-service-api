# ADR 0001: Job execution model

- **Status:** Accepted
- **Date:** 2026-11-04

## Context

`POST /environments` and `DELETE /environments/{id}` trigger Terraform and Ansible runs that take
minutes. The API must answer immediately (202 Accepted), run the work in the background, report
progress, and behave predictably when a worker crashes or hangs mid-run. A half-finished
`terraform apply` is the dangerous case: it may have changed real infrastructure, and running a
second apply concurrently against the same state can corrupt it.

The project should run with nothing more than Docker locally, but the design must not block
moving execution to a queue or an external automation platform later.

## Decision

### Jobs live in PostgreSQL; execution is in-process

- A `jobs` table in PostgreSQL is the **source of truth** for every job (see [ADR 0003](0003-postgresql-source-of-truth-and-audit-outbox.md)).
- Workers run inside the API process on **Java virtual threads**. Each job runs its steps as
  child processes (`terraform`, `ansible-playbook`).
- Controllers never talk to a runner. They write a job row (status `PENDING`) in the same
  transaction as the environment change. Workers pick jobs up from the table.

### Claiming: `SELECT ... FOR UPDATE SKIP LOCKED`, then a lease

The claim is a **short** transaction. The row lock is not held for the duration of the job
(a 4-minute apply would pin a pooled connection and an open transaction).

```sql
UPDATE jobs
SET status = 'RUNNING', phase = 'PLANNING',
    claimed_by = :workerId,
    lease_version = lease_version + 1,          -- new fencing token
    lease_expires_at = now() + :leaseDuration,
    attempt = attempt + 1
WHERE id = (
    SELECT id FROM jobs
    WHERE status = 'PENDING'
    ORDER BY created_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED                       -- concurrent claimers skip, never block
)
RETURNING id, lease_version;
```

- **Heartbeat:** while the job runs, the worker renews `lease_expires_at` periodically
  (proposed: lease 60 s, renewal every 20 s, both configurable).
- **Fencing token:** `lease_version` increases on every claim. *Every* update a worker makes
  (heartbeat, phase change, completion) includes
  `WHERE id = :id AND lease_version = :myVersion` and checks that exactly one row changed.
  Zero rows means the lease was lost: the worker stops immediately.
- **Lost lease stops the child process (proposed):** if a heartbeat finds the lease lost, the
  worker sends SIGTERM to the running `terraform`/`ansible-playbook` process. Terraform handles
  SIGTERM gracefully (finishes the current resource operation, writes state, releases its lock).
  Without this, a zombie worker could keep applying after another worker took over.

### Phases and states

Job `type`: `PROVISION` | `DESTROY`. While `RUNNING`, a job is in one `phase`:

| Phase | Steps | Changes infrastructure? |
|---|---|---|
| `PLANNING` | `terraform init`, `terraform plan -out=...` | No |
| `APPLYING` | `terraform apply <saved plan>` | **Yes** |
| `CONFIGURING` | inventory generation, `ansible-playbook` (PROVISION only) | Yes (host config) |

Job `status`: `PENDING → RUNNING → SUCCEEDED | FAILED | INTERRUPTED`.
`FAILED` and `INTERRUPTED` can return to `PENDING` through a user action (retry / resume).

### Recovery of expired leases

A recovery sweep runs **at startup and periodically** (proposed: every lease duration), so a
hung worker is detected without waiting for a restart. For each `RUNNING` job whose lease expired:

| Phase when the lease expired | Outcome | Why |
|---|---|---|
| `PLANNING` | Back to `PENDING` automatically (proposed: at most 3 attempts, then `FAILED`) | Plan does not change infrastructure, so repeating it is safe |
| `APPLYING` | `INTERRUPTED` | Infrastructure may be half-changed; a human decides |
| `CONFIGURING` | `INTERRUPTED` (proposed) | Same rule: only `PLANNING` is auto-retried |

When a job becomes `INTERRUPTED`, the recovery releases the environment's Terraform state lock
(`terraform force-unlock`). This is safe only because the expired lease proves no worker owns the
job any more. With local state (Floci) there is nothing to release: Terraform's lock is an OS file
lock that disappears with the process. With the S3 backend, a crashed process leaves the lock file
behind; the lock ID is taken from the lock object (or from Terraform's lock error) before unlocking.

### Resume vs retry (user actions on `INTERRUPTED` / `FAILED` jobs)

- **Resume:** continue from the interrupted phase. Phases already completed are skipped
  (interrupted in `CONFIGURING` means only Ansible runs again). An interrupted `APPLYING` phase
  re-plans first: a saved plan is stale once state may have changed, and Terraform rejects it.
- **Retry:** start the job again from `PLANNING`.

Both are safe to repeat because Terraform and Ansible converge on the desired state.

### Generic `ProvisioningRunner` interface

Workers hand a claimed job to a `ProvisioningRunner`. The contract is about **dispatching and
reporting**, not about running processes, so runners with different execution models fit:

- A runner reports progress through a callback (phase started, phase completed, failure, and an
  optional external reference such as a queue message ID or a remote job ID).
- A runner returns either a final result (completed / failed) or **delegated** (the work
  continues elsewhere; completion arrives later through a listener or a poller and updates the
  job with the same fencing rules).
- Runner selection is configuration, per job type. Controllers and the job table are unaware of
  which runner executes a job.

First implementation: `InProcessTerraformAnsibleRunner`.

## Consequences

**Good**
- Runs with Docker alone: no queue, broker, or extra service to operate.
- Job state survives restarts. Crashes are detected and classified by phase, so the dangerous
  case (interrupted apply) always involves a human, and the safe case (planning) heals itself.
- Several API instances can run workers at the same time: `SKIP LOCKED` and fencing tokens keep
  them from stepping on each other.
- Execution can move elsewhere later without touching controllers or the job model.

**Bad / accepted risks**
- Execution capacity is tied to API instances: a slow job uses an API host's CPU and memory.
- Terraform itself does not check our fencing token. Protection relies on the token check
  between steps, SIGTERM on lease loss, and Terraform's own state lock as a second line of defense.
- **Orphaned resources:** if a process dies after AWS created a resource but before Terraform
  wrote state, a resume or retry may create a duplicate and orphan the original. Mitigation:
  every resource is tagged with `EnvironmentId` and `JobId`; before resuming an interrupted
  apply on real AWS, look for tagged resources missing from state and import or remove them.
  Not automated in this phase.
- The API host needs `terraform`, `ansible-playbook` and the SSH key, which matters for packaging.

## Alternatives considered

- **Status in memory, work on `@Async` threads only:** simplest, but every restart loses jobs and
  their status. Rejected.
- **Holding `FOR UPDATE` for the whole job:** gives exclusivity for free, but keeps a transaction
  and a pooled connection open for minutes, and a dead process holds the lock until the
  connection times out. Rejected in favor of short claims with leases.
- **Recovery only at startup:** unsafe with more than one instance (a starting instance would mark
  another instance's live job as interrupted) and blind to hung workers without a restart.
  Rejected in favor of lease expiry.
- **Releasing the Terraform lock at resume instead of at recovery:** would keep the lock as a
  visible "interrupted" marker until a human acts. Not chosen; the lease is the source of truth
  for ownership, and the state is not touched while the job is `INTERRUPTED`.
- **Quartz / Spring Batch / Temporal:** capable job frameworks, but more machinery than this
  workload needs. Temporal would be the next step if workflows grew long-running and multi-step
  across services.

## Next steps

1. **`SqsDispatchRunner`:** claimed jobs are sent to an SQS queue; a separate worker service
   (or a container task) runs Terraform and Ansible and reports back. Separates execution capacity
   from the API, allows scaling workers independently, and keeps tools and credentials off the
   API hosts. The job row stays the source of truth; queue messages carry the job ID and the
   fencing token. Floci supports SQS, so this can be built and tested locally.
2. **`AnsibleAutomationPlatformRunner`:** for organizations that already run Ansible Automation
   Platform (or AWX), the runner launches an AAP job template through its REST API, stores the
   AAP job ID as the external reference, and a poller maps AAP job status to our phases. Gives
   central credentials, RBAC and execution environments managed by the platform team.
3. **Orphan detection by tags** before resuming an interrupted apply on real AWS.
