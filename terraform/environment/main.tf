# Root module for ONE environment, run by the API's jobs (never by hand on real
# AWS). It reads what the platform publishes in SSM, never the platform state.

locals {
  name_prefix = "${var.project_name}-${var.target}"
  ssm_prefix  = "/${var.project_name}/${var.target}/platform"

  default_tags = {
    Project       = var.project_name
    Target        = var.target
    ManagedBy     = "terraform"
    Layer         = "environment"
    EnvironmentId = var.environment_id
    # Updated by every job that applies. After a crash, resources tagged with the
    # crashed job's ID but missing from state are orphans (ADR 0001).
    JobId = var.job_id
  }

  # On Floci the source CIDR is not enforced, so a loopback placeholder is fine
  # when the caller supplies nothing.
  ssh_ingress_cidrs = length(var.ssh_ingress_cidrs) > 0 ? var.ssh_ingress_cidrs : ["127.0.0.1/32"]
}

# These are plain String parameters (IDs, names), so insecure_value is used: .value
# is always marked sensitive, which would hide them in plans and outputs.
data "aws_ssm_parameter" "platform" {
  for_each = toset(["vpc-id", "subnet-id", "artifacts-bucket"])

  name = "${local.ssm_prefix}/${each.key}"
}

# Same lookup on both targets: Floci's AMI catalog answers Canonical's owner ID and
# name pattern (returning a Docker ubuntu:22.04 image), real AWS returns the latest
# official Ubuntu 22.04 AMI.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

module "environment" {
  source = "../modules/environment"

  name                  = var.name
  name_prefix           = local.name_prefix
  vpc_id                = data.aws_ssm_parameter.platform["vpc-id"].insecure_value
  subnet_id             = data.aws_ssm_parameter.platform["subnet-id"].insecure_value
  ami_id                = data.aws_ami.ubuntu.id
  instance_type         = var.instance_type
  instance_count        = var.instance_count
  ssh_public_key        = file(pathexpand(var.ssh_public_key_path))
  ssh_ingress_cidrs     = local.ssh_ingress_cidrs
  manage_ebs_encryption = !local.is_floci
}
