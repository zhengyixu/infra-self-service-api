# Same lookup on both targets: Floci's AMI catalog answers Canonical's owner ID and
# name pattern (returning ami-ubuntu2204, a Docker ubuntu:22.04 image), real AWS
# returns the latest official Ubuntu 22.04 AMI.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_key_pair" "deployer" {
  key_name   = "${local.name_prefix}-deployer"
  public_key = file(pathexpand(var.ssh_public_key_path))

  lifecycle {
    # Floci does not persist tags supplied at ImportKeyPair, so default_tags would
    # show as a perpetual diff. Key pair tags are cosmetic; ignoring them is safe.
    ignore_changes = [tags, tags_all]
  }
}

resource "aws_instance" "node" {
  count = var.instance_count

  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
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
    # Floci does not model EBS encryption (always reports false), which would force
    # instance replacement on every plan. null = "not managed" on Floci; enforced on AWS.
    encrypted = local.is_floci ? null : true
  }

  tags = {
    Name = "${local.name_prefix}-node-${count.index + 1}"
    Role = "app"
  }
}
