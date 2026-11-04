# Applied once per target by a human: the S3 bucket that holds every other
# Terraform state (platform and one per environment). Locking uses S3 lock files
# (use_lockfile), so no DynamoDB lock table is needed.

locals {
  default_tags = {
    Project   = var.project_name
    Target    = var.target
    ManagedBy = "terraform"
    Layer     = "bootstrap"
  }

  # Deterministic name, so backend config files can name it: account ID and region
  # make it globally unique without a random suffix.
  state_bucket = "${var.project_name}-tfstate-${data.aws_caller_identity.current.account_id}-${var.region}"
}

module "state_bucket" {
  source = "../modules/s3-bucket"

  bucket = local.state_bucket
  # Never on real AWS: losing state means losing track of real resources.
  force_destroy = local.is_floci

  tags = {
    Name = local.state_bucket
  }
}
