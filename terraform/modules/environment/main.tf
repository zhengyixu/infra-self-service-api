# One requested environment: its own security group, key pair and instances,
# placed in the shared platform network. Each environment has its own state
# (docs/adr/0002-terraform-layout-and-state.md), so destroying one never touches
# another or the platform.

locals {
  prefix = "${var.name_prefix}-${var.name}"
}

resource "aws_security_group" "instance" {
  name        = "${local.prefix}-instance"
  description = "SSH in from allowed CIDRs; all egress (package installs)."
  vpc_id      = var.vpc_id

  tags = {
    Name = "${local.prefix}-instance"
  }
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = toset(var.ssh_ingress_cidrs)

  security_group_id = aws_security_group.instance.id
  description       = "SSH from ${each.value}"
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  cidr_ipv4         = each.value
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.instance.id
  description       = "All outbound"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_key_pair" "deployer" {
  key_name   = "${local.prefix}-deployer"
  public_key = var.ssh_public_key

  lifecycle {
    # Floci does not persist tags supplied at ImportKeyPair, so default_tags would
    # show as a perpetual diff. Key pair tags are cosmetic; ignoring them is safe.
    ignore_changes = [tags, tags_all]
  }
}

resource "aws_instance" "node" {
  count = var.instance_count

  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.instance.id]
  key_name               = aws_key_pair.deployer.key_name

  # IMDSv2 only: blocks SSRF-style credential theft via the metadata endpoint.
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    # null = "not managed": Floci always reports false, which would force
    # replacement on every plan. Enforced wherever encryption is modeled.
    encrypted = var.manage_ebs_encryption ? true : null
  }

  tags = {
    Name = "${local.prefix}-node-${count.index + 1}"
    Role = "app"
  }
}
