locals {
  is_floci = var.target == "floci"

  # On Floci the source CIDR is not enforced, so a loopback placeholder is fine
  # when the caller supplies nothing.
  ssh_ingress_cidrs = length(var.ssh_ingress_cidrs) > 0 ? var.ssh_ingress_cidrs : ["127.0.0.1/32"]

  name_prefix = "${var.project_name}-${var.target}"
}

provider "aws" {
  region = var.region

  # Floci accepts any non-empty credentials. On real AWS these are null, so the
  # provider uses the standard credential chain (env vars, SSO, or GitHub OIDC in CI).
  access_key = local.is_floci ? "test" : null
  secret_key = local.is_floci ? "test" : null

  # Floci has no real account/metadata service to validate against.
  skip_credentials_validation = local.is_floci
  skip_metadata_api_check     = local.is_floci
  skip_requesting_account_id  = local.is_floci
  s3_use_path_style           = local.is_floci

  # Only point at the emulator when target = "floci". Every service this
  # configuration calls must be listed, or the provider would hit real AWS.
  dynamic "endpoints" {
    for_each = local.is_floci ? [1] : []
    content {
      dynamodb = var.floci_endpoint
      ec2      = var.floci_endpoint
      iam      = var.floci_endpoint
      s3       = var.floci_endpoint
      sts      = var.floci_endpoint
    }
  }

  default_tags {
    tags = {
      Project   = var.project_name
      Target    = var.target
      ManagedBy = "terraform"
    }
  }
}
