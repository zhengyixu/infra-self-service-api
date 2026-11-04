# Artifacts that Ansible deploys to instances (configs, app bundles).
module "artifacts_bucket" {
  source = "../modules/s3-bucket"

  # bucket_prefix: S3 names are global, so AWS appends a unique suffix.
  bucket_prefix = "${local.name_prefix}-artifacts-"
  force_destroy = true # demo project: allow destroy with objects inside

  tags = {
    Name = "${local.name_prefix}-artifacts"
  }
}

# Downstream copy of the API's audit_events outbox (ADR 0003). PostgreSQL is the
# source of truth; a publisher (later phase) writes each event here once, keyed by
# the event's UUID, so redelivery overwrites instead of duplicating.
resource "aws_dynamodb_table" "audit_events" {
  name         = "${local.name_prefix}-audit-events"
  billing_mode = "PAY_PER_REQUEST" # no capacity planning, ~free at demo volume
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = {
    Name = "${local.name_prefix}-audit-events"
  }
}
