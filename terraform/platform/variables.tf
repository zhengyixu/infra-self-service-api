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

variable "vpc_cidr" {
  description = "CIDR block for the shared VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block for the public subnet environments are placed in."
  type        = string
  default     = "10.20.1.0/24"
}
