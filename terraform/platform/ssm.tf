# The platform's published interface. Environment roots read these parameters
# instead of the platform state (terraform_remote_state), so the API's role needs
# read access to /<project>/<target>/platform/* only, never to the state file.
# Changing a name here is a breaking change for environment/.

locals {
  ssm_prefix = "/${var.project_name}/${var.target}/platform"

  published = {
    "vpc-id"           = aws_vpc.main.id
    "subnet-id"        = aws_subnet.public.id
    "artifacts-bucket" = module.artifacts_bucket.id
  }
}

resource "aws_ssm_parameter" "published" {
  for_each = local.published

  name        = "${local.ssm_prefix}/${each.key}"
  description = "Published by terraform/platform for environment roots."
  type        = "String"
  value       = each.value
}
