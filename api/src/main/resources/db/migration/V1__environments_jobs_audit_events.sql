-- Environments, their provisioning jobs, and the audit outbox.
-- Design: docs/adr/0001-job-execution-model.md, docs/adr/0003-postgresql-source-of-truth-and-audit-outbox.md
--
-- Conventions:
--   * IDs are UUIDv7 (time-ordered, PostgreSQL 18 built-in): index-friendly like a sequence,
--     but safe to expose in URLs and generate on any node.
--   * Statuses are text + CHECK rather than PostgreSQL enum types: readable in psql, and adding
--     a value is a one-line constraint change instead of ALTER TYPE.
--   * updated_at is set by the application's UPDATE statements (no triggers), so every write
--     is visible in the Java code.

CREATE TABLE environments (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    name           text        NOT NULL,
    instance_type  text        NOT NULL,
    instance_count integer     NOT NULL CHECK (instance_count BETWEEN 1 AND 3),
    status         text        NOT NULL
        CHECK (status IN ('PROVISIONING', 'READY', 'FAILED', 'DESTROYING', 'DESTROYED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Names are unique among live environments; a destroyed environment's name can be reused.
-- Enforced here (not with a SELECT first) so two concurrent requests cannot both succeed.
CREATE UNIQUE INDEX environments_name_live_uq ON environments (name) WHERE status <> 'DESTROYED';

CREATE TABLE jobs (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    environment_id   uuid        NOT NULL REFERENCES environments (id),
    type             text        NOT NULL CHECK (type IN ('PROVISION', 'DESTROY')),
    status           text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    -- Only meaningful while RUNNING, and kept afterwards to show where a job stopped.
    phase            text        CHECK (phase IN ('PLANNING', 'APPLYING', 'CONFIGURING')),
    attempt          integer     NOT NULL DEFAULT 0,
    -- Lease (ADR 0001): who owns the job, until when, and the fencing token.
    claimed_by       text,
    lease_version    bigint      NOT NULL DEFAULT 0,
    lease_expires_at timestamptz,
    -- Set by runners that delegate work elsewhere (queue message ID, AAP job ID).
    external_ref     text,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT jobs_running_has_lease CHECK (
        status <> 'RUNNING'
        OR (phase IS NOT NULL AND claimed_by IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

-- At most one PENDING or RUNNING job per environment: no concurrent applies on one state.
-- INTERRUPTED does not count, so a user can still destroy an environment whose apply was
-- interrupted; resuming it while another job is active is then rejected by this index.
CREATE UNIQUE INDEX jobs_one_active_per_environment_uq
    ON jobs (environment_id) WHERE status IN ('PENDING', 'RUNNING');

-- Claim query: oldest PENDING job first.
CREATE INDEX jobs_pending_idx ON jobs (created_at) WHERE status = 'PENDING';
-- Recovery sweep: RUNNING jobs whose lease expired.
CREATE INDEX jobs_running_lease_idx ON jobs (lease_expires_at) WHERE status = 'RUNNING';
-- Latest job of an environment (GET /environments/{id}); also covers the foreign key.
CREATE INDEX jobs_environment_idx ON jobs (environment_id, created_at);

-- Transactional outbox: written in the same transaction as each status change.
-- No foreign key on aggregate_id: it points at either table, and audit history must
-- outlive the rows it describes.
CREATE TABLE audit_events (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    aggregate_type text        NOT NULL CHECK (aggregate_type IN ('ENVIRONMENT', 'JOB')),
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL DEFAULT '{}',
    -- Set by the DynamoDB publisher (later phase). id is the idempotency key there.
    published_at   timestamptz
);

CREATE INDEX audit_events_unpublished_idx ON audit_events (occurred_at) WHERE published_at IS NULL;
CREATE INDEX audit_events_aggregate_idx ON audit_events (aggregate_id, occurred_at);
