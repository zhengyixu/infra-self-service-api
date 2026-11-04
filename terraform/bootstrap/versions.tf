terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Local state by necessity: this root creates the bucket every other state lives in.
  # One state file per target; scripts/tf.sh passes path=terraform.<target>.tfstate.
  backend "local" {}
}
