# ADR 0003: PostgreSQL as source of truth, with an audit outbox

- **Status:** Accepted
- **Date:** 2026-11-04

## Context

The original plan recorded environment requests in DynamoDB. Job execution (ADR 0001) needs
row-level locking (`FOR UPDATE SKIP LOCKED`) and transactions that span the environment, its job
and an audit record. Writing the same change to two databases ("dual write") without a
transaction across them leaves them disagreeing whenever one write fails.

## Decision

- **PostgreSQL is the source of truth** for `environments` and `jobs`.
- Every status change also inserts a row into **`audit_events`** in the **same transaction**
  (transactional outbox). The audit row and the state change are committed together or not at all.
- A **DynamoDB publisher** comes in a later phase: it reads unpublished `audit_events` rows,
  writes them to the DynamoDB table created by `terraform/platform/`, and marks them published.
  Delivery is at-least-once, so events carry a unique ID and the publisher writes idempotently.
- Schema changes are versioned migrations (Flyway), applied at startup.
- Local: PostgreSQL in `docker-compose.yml`. Real AWS: RDS, smallest single-AZ instance,
  managed by Terraform so it can be destroyed after demos.

## Consequences

- One transactional store for everything the API decides. No dual-write inconsistency.
- DynamoDB becomes a downstream, eventually consistent copy that can be rebuilt from the outbox.
- An extra service to run locally and in AWS (RDS cost while it exists).

## Alternatives considered

- **DynamoDB only:** conditional writes could implement leases, but there is no
  `SKIP LOCKED` equivalent for fair concurrent claiming, and multi-item transactions are limited.
  Rejected for the job table.
- **Write PostgreSQL and DynamoDB directly in the request path:** dual write. Rejected.
