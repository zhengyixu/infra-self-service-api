terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Local state for now, one workspace per target (see providers.tf).
  # Phase 7 moves the aws workspace to a remote S3 backend.
}
