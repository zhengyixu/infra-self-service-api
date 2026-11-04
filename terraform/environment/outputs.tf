# Shape consumed by ansible/inventory/generate.py.

output "target" {
  description = "Deployment target of this state."
  value       = var.target
}

output "environment_id" {
  description = "Environment UUID."
  value       = var.environment_id
}

output "environment_name" {
  description = "Environment name."
  value       = var.name
}

output "artifacts_bucket" {
  description = "Platform artifacts bucket (from SSM)."
  value       = data.aws_ssm_parameter.platform["artifacts-bucket"].insecure_value
}

output "ssh_user" {
  description = "SSH login user. Floci injects the key for root; Ubuntu AMIs on AWS use ubuntu."
  value       = local.is_floci ? "root" : "ubuntu"
}

output "instances" {
  description = "Instances keyed by Name tag."
  value       = module.environment.instances
}
