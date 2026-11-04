# Identical in bootstrap/, platform/ and environment/ (a root module cannot share
# provider configuration with another root). Each root defines local.default_tags.

locals {
  is_floci = var.target == "floci"
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

  # Only point at the emulator when target = "floci". Every service these roots
  # call must be listed, or the provider would hit real AWS.
  dynamic "endpoints" {
    for_each = local.is_floci ? [1] : []
    content {
      dynamodb = var.floci_endpoint
      ec2      = var.floci_endpoint
      iam      = var.floci_endpoint
      s3       = var.floci_endpoint
      ssm      = var.floci_endpoint
      sts      = var.floci_endpoint
    }
  }

  default_tags {
    tags = local.default_tags
  }
}

# Guard: target must match the account we are actually talking to. Floci always
# reports account 000000000000. Catches e.g. target = "aws" while AWS_ENDPOINT_URL
# still points at Floci, or target = "floci" with real credentials and no endpoints.
data "aws_caller_identity" "current" {
  lifecycle {
    postcondition {
      condition     = (self.account_id == "000000000000") == local.is_floci
      error_message = "target = \"${var.target}\" but the provider is connected to account ${self.account_id}. Check target and your AWS credentials/endpoint."
    }
  }
}
