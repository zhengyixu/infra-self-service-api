output "vpc_id" {
  description = "Shared VPC ID."
  value       = aws_vpc.main.id
}

output "subnet_id" {
  description = "Subnet environments are placed in."
  value       = aws_subnet.public.id
}

output "artifacts_bucket" {
  description = "S3 bucket for deployable artifacts."
  value       = module.artifacts_bucket.id
}

output "audit_events_table" {
  description = "DynamoDB table receiving published audit events."
  value       = aws_dynamodb_table.audit_events.name
}

output "ssm_prefix" {
  description = "SSM path under which the platform publishes values for environments."
  value       = local.ssm_prefix
}
