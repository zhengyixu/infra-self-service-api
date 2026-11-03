# Artifact bucket: configs/app bundles that Ansible deploys to instances.
# bucket_prefix avoids global-name collisions on real AWS (S3 names are global).
resource "aws_s3_bucket" "artifacts" {
  bucket_prefix = "${local.name_prefix}-artifacts-"
  force_destroy = true # demo project: allow `terraform destroy` with objects inside

  tags = {
    Name = "${local.name_prefix}-artifacts"
  }
}

# TODO(you): harden the artifacts bucket. Add three resources, each referencing
# aws_s3_bucket.artifacts.id:
#   1. aws_s3_bucket_public_access_block: all four block_*/ignore_*/restrict_* flags = true
#   2. aws_s3_bucket_server_side_encryption_configuration: SSE-S3 ("AES256")
#   3. aws_s3_bucket_versioning: status = "Enabled"
# Hint: since AWS provider v4 these are separate resources, not inline blocks on
# aws_s3_bucket. Verify on Floci afterwards with:
#   aws s3api get-bucket-versioning --bucket "$(terraform output -raw artifacts_bucket)"
# Interview angle: which of these does real AWS already apply by default for new
# buckets, and why still declare them explicitly?

# Environment requests recorded by the Spring Boot API (Phase 4).
resource "aws_dynamodb_table" "environments" {
  name         = "${local.name_prefix}-environments"
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
    Name = "${local.name_prefix}-environments"
  }
}
