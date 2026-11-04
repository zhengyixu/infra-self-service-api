variable "name" {
  description = "Environment name (unique among live environments; DNS-label style). Used in resource names."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}[a-z0-9]$", var.name))
    error_message = "name must be 3-32 lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "name_prefix" {
  description = "Prefix for resource names, e.g. infra-ssa-floci."
  type        = string
}

variable "vpc_id" {
  description = "Platform VPC to place the environment in."
  type        = string
}

variable "subnet_id" {
  description = "Platform subnet for the instances."
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

  validation {
    condition     = var.instance_count >= 1 && var.instance_count <= 3
    error_message = "instance_count must be between 1 and 3."
  }
}

variable "ami_id" {
  description = "AMI for the instances."
  type        = string
}

variable "ssh_public_key" {
  description = "Public key installed on the instances (the private key never reaches Terraform)."
  type        = string
}

variable "ssh_ingress_cidrs" {
  description = "CIDRs allowed to reach SSH."
  type        = list(string)

  validation {
    condition     = length(var.ssh_ingress_cidrs) > 0 && !contains(var.ssh_ingress_cidrs, "0.0.0.0/0")
    error_message = "Set at least one CIDR; 0.0.0.0/0 is not allowed."
  }
}

variable "manage_ebs_encryption" {
  description = "Enforce encrypted root volumes. False only on emulators that do not model EBS encryption."
  type        = bool
  default     = true
}
