data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }

  lifecycle {
    # Each target keeps its own state (workspace). This stops e.g. `-var target=aws`
    # from being planned against the floci state, which would mix emulator and
    # real resources in one state file.
    precondition {
      condition     = terraform.workspace == var.target
      error_message = "Workspace \"${terraform.workspace}\" does not match target \"${var.target}\". Run: terraform workspace select -or-create ${var.target}"
    }
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidr
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = true

  tags = {
    Name = "${local.name_prefix}-public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${local.name_prefix}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "instance" {
  name        = "${local.name_prefix}-instance"
  description = "SSH in from allowed CIDRs; all egress (package installs)."
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-instance"
  }
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  for_each = toset(local.ssh_ingress_cidrs)

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
