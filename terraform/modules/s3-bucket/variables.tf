variable "bucket" {
  description = "Exact bucket name. Set this or bucket_prefix, not both."
  type        = string
  default     = null
}

variable "bucket_prefix" {
  description = "Name prefix; AWS appends a unique suffix (S3 names are global)."
  type        = string
  default     = null
}

variable "force_destroy" {
  description = "Allow terraform destroy to delete a bucket that still has objects. Never true for state buckets on real AWS."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Extra tags (provider default_tags still apply)."
  type        = map(string)
  default     = {}
}
