terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial configuration: bucket, endpoint etc. come from <target>.s3.tfbackend,
  # because a backend block cannot use variables. Use scripts/tf.sh, which pairs
  # the backend file with the matching target.
  backend "s3" {}
}
