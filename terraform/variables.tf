variable "target" {
  description = "Where to deploy: \"floci\" (local emulator) or \"aws\" (real AWS)."
  type        = string
  default     = "floci"

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
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block for the public subnet."
  type        = string
  default     = "10.20.1.0/24"
}

variable "instance_type" {
  description = "EC2 instance type. Smallest sensible default to keep real-AWS cost minimal."
  type        = string
  default     = "t3.micro"
}

variable "instance_count" {
  description = "Number of EC2 instances to create."
  type        = number
  default     = 1

  validation {
    condition     = var.instance_count >= 0 && var.instance_count <= 3
    error_message = "instance_count must be between 0 and 3."
  }
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
