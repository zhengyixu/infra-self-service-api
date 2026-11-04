# One place that decides what "a bucket in this project" means. Used for the
# Terraform state bucket (bootstrap/) and the artifacts bucket (platform/).

resource "aws_s3_bucket" "this" {
  bucket        = var.bucket
  bucket_prefix = var.bucket_prefix
  force_destroy = var.force_destroy
  tags          = var.tags

  lifecycle {
    precondition {
      condition     = (var.bucket == null) != (var.bucket_prefix == null)
      error_message = "Set exactly one of bucket or bucket_prefix."
    }
  }
}

# TODO(you): harden the bucket. Add three resources, each with
# bucket = aws_s3_bucket.this.id:
#   1. aws_s3_bucket_public_access_block: all four block_*/ignore_*/restrict_* flags = true
#   2. aws_s3_bucket_server_side_encryption_configuration: SSE-S3 ("AES256")
#   3. aws_s3_bucket_versioning: status = "Enabled"
# Hints: since AWS provider v4 these are separate resources, not inline blocks on
# aws_s3_bucket. Versioning matters most for the state bucket: it is how you
# recover a state file that a bad apply or a bug overwrote.
# Verify on Floci afterwards (bucket name from the bootstrap output):
#   aws s3api get-bucket-versioning --bucket <name>
#   aws s3api get-bucket-encryption --bucket <name>
#   aws s3api get-public-access-block --bucket <name>
# Interview angle: which of these does real AWS already apply by default for new
# buckets, and why still declare them explicitly? (CLAUDE_notes.md, section 5)
