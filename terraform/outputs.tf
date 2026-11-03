output "target" {
  description = "Deployment target of this state."
  value       = var.target
}

output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.main.id
}

output "artifacts_bucket" {
  description = "S3 bucket for deployable artifacts."
  value       = aws_s3_bucket.artifacts.bucket
}

output "environments_table" {
  description = "DynamoDB table for environment requests."
  value       = aws_dynamodb_table.environments.name
}

output "ssh_user" {
  description = "SSH login user. Floci injects the key for root; Ubuntu AMIs on AWS use ubuntu."
  value       = local.is_floci ? "root" : "ubuntu"
}

# Consumed by Ansible (Phase 3) to build its inventory.
output "instances" {
  description = "Instances keyed by Name tag."
  value = {
    for i in aws_instance.node : i.tags["Name"] => {
      id         = i.id
      public_ip  = i.public_ip
      private_ip = i.private_ip
    }
  }
}
