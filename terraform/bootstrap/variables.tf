variable "target" {
  description = "Where to deploy: \"floci\" (local emulator) or \"aws\" (real AWS)."
  type        = string

  validation {
    condition     = contains(["floci", "aws"], var.target)
    error_message = "target must be \"floci\" or \"aws\"."
  }
}

variable "floci_endpoint" {
  description = "Floci endpoint URL. Only used when target = \"floci\"."
  type        = string
  default     = "http://localhost:4566"
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix for resource names and the Project tag."
  type        = string
  default     = "infra-ssa"
}
