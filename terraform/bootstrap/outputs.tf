output "state_bucket" {
  description = "Bucket for all other Terraform states. Put it in <layer>/<target>.s3.tfbackend."
  value       = module.state_bucket.id
}
