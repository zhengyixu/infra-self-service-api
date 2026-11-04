output "security_group_id" {
  description = "Security group of the environment's instances."
  value       = aws_security_group.instance.id
}

output "instances" {
  description = "Instances keyed by Name tag (consumed by the Ansible inventory generator)."
  value = {
    for i in aws_instance.node : i.tags["Name"] => {
      id         = i.id
      public_ip  = i.public_ip
      private_ip = i.private_ip
    }
  }
}
