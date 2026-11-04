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

variable "environment_id" {
  description = "Environment UUID from the API (tag and state key)."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.environment_id))
    error_message = "environment_id must be a lowercase UUID."
  }
}

variable "job_id" {
  description = "UUID of the API job running this plan/apply (tag only; finds orphans after a crash)."
  type        = string
}

variable "name" {
  description = "Environment name from the API request."
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type."
  type        = string
  default     = "t3.micro"
}

variable "instance_count" {
  description = "Number of instances."
  type        = number
  default     = 1
}

variable "ssh_public_key_path" {
  description = "Path to the SSH public key installed on instances (private key stays local)."
  type        = string
  default     = "~/.ssh/infra-self-service-api.pub"
}

variable "ssh_ingress_cidrs" {
  description = "CIDRs allowed to SSH to instances. Required (and must not be open to the world) on real AWS."
  type        = list(string)
  default     = []

  validation {
    condition     = var.target != "aws" || (length(var.ssh_ingress_cidrs) > 0 && !contains(var.ssh_ingress_cidrs, "0.0.0.0/0"))
    error_message = "On target = \"aws\", set ssh_ingress_cidrs to your own IP (e.g. [\"203.0.113.10/32\"]); 0.0.0.0/0 is not allowed."
  }
}
