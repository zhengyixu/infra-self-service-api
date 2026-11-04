terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial configuration: <target>.s3.tfbackend plus a per-environment key, e.g.
  #   -backend-config=key=environments/<environment-id>/terraform.tfstate
  # One state per environment (ADR 0002). The API passes these on every job.
  backend "s3" {}
}
