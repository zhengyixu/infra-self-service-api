# Shared infrastructure every environment uses. Changed through CI only; the API
# never plans or applies this root (docs/adr/0002-terraform-layout-and-state.md).

locals {
  name_prefix = "${var.project_name}-${var.target}"

  default_tags = {
    Project   = var.project_name
    Target    = var.target
    ManagedBy = "terraform"
    Layer     = "platform"
  }
}
