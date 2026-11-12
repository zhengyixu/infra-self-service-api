# Floci vs real AWS: observed differences

Running log of behavior seen on the Floci emulator that must be handled in code
or re-verified on real AWS. Feeds the README's "emulator vs real AWS" section.

| Area | On Floci | Implication |
|------|----------|-------------|
| Identity | `sts get-caller-identity` returns account `000000000000`, ARN `...:root`, for any credentials | IAM auth/policies are not proven locally; verify least-privilege on real AWS |
| AMIs | Synthetic IDs, e.g. `ami-ubuntu2204`, `ami-0abcdef1234567890` | Never hard-code AMI IDs; look them up via `data "aws_ami"` filters |
| Default VPC | Pre-created as `vpc-default-us-east-1` (172.31.0.0/16) | Don't depend on default-VPC IDs or format; create our own VPC |
| Persistence | `FLOCI_STORAGE_MODE=memory`: state is lost on container restart | Terraform state and emulator state can drift after a restart; re-apply from clean |
| AMI lookup | `data "aws_ami"` with Canonical owner `099720109477` + jammy name filter resolves to `ami-ubuntu2204` (Docker `ubuntu:22.04`) | Same lookup works on both targets; no per-target AMI branch needed |
| EC2 runtime | Instance = Docker container running `tail -f /dev/null`; no systemd, no cloud-init | Ansible tasks relying on systemd services need verification on real AWS (Phase 3) |
| SSH | Key injected for **root**; sshd installed at boot (~10-15 s of `Connection reset`); container IP reachable on :22 from a Linux host, also published on `localhost:2200-2299` | Inventory: `root` on Floci vs `ubuntu` on AWS; add an SSH wait/retry. Container-IP routing may not work on Docker Desktop (macOS/Windows) |
| Public IP | `PublicIpAddress` = private container IP (e.g. `10.20.1.10`) | IGW/route table/public-IP routing are not proven locally |
| Security groups | Source CIDRs not enforced (unless `FLOCI_NETWORK_SECURITY_GROUP_ENFORCEMENT_ENABLED=true`) | SSH CIDR restrictions must be verified on real AWS |
| EBS encryption | `root_block_device.encrypted = true` is accepted but reported as `false` | Perpetual replacement diff; set `encrypted` only when `target = "aws"` |
| Key pair tags | Tags passed at `ImportKeyPair` (incl. provider `default_tags`) are not stored | Perpetual tag diff; `ignore_changes = [tags, tags_all]` on the key pair |
| Port 80 in instances | Floci's IMDS proxy (`socat`) listens on `169.254.169.254:80` *inside* each instance, so apps cannot bind `0.0.0.0:80` | App serves on 8080. On real EC2, IMDS is answered by the hypervisor, so port 80 is free |
| Service manager | PID 1 is `tail`; Ansible reports `ansible_service_mgr: tail`. `ansible.builtin.service` falls back to SysV `/etc/init.d` scripts, which work for apt packages like nginx | Same role works on both, but systemd-only features (unit files, timers, `systemd` module) can't be tested locally |
| sudo | Not installed in the base image; SSH login is already root | Base role installs `sudo`. `become: true` works on Floci because Ansible skips escalation when already root |
| S3 conditional writes | `PutObject` with `If-None-Match: *` on an existing key returns `412 PreconditionFailed`. Terraform's `use_lockfile` works: a second run fails with "Error acquiring the state lock" while the first holds `<key>.tflock`, and the lock object is removed afterwards | Same mechanism as real S3 (supported since Nov 2024); re-check lock behavior on real AWS in Phase 7 |
| VPC networks | Each VPC becomes a Docker network with the VPC's CIDR, created when the first instance launches. `DeleteVpc` leaves the Docker network behind (Floci's own container stays attached to it) | Overlapping VPC CIDRs, legal on AWS, would collide on Docker. After destroying a VPC: `docker network disconnect <net> <floci-container> && docker network rm <net>` (seen with floci 2.3.0) |
| SSM Parameter Store | `String` parameters work (put/get/delete, Terraform resource and data source) | Platform-to-environment handoff testable locally; IAM scoping of `/project/target/platform/*` only provable on AWS |

## Why Floci and not AWS Emulator

AWS published its own emulator, [AWS Emulator](https://github.com/aws/aws-emulator)
(CC0, Rust), in November 2026. It was evaluated on 2026-11-11 against release v1.0.322, by
reading its source and its tracked issues, not by running it:

| Question | AWS Emulator | Floci |
|----------|--------------|-------|
| Do EC2 instances run? | No. `RunInstances` stores a record that moves from `pending` to `running` on a timer; the guest boot is simulated. No container, no sshd, nothing to connect to | Yes. Each instance is a Docker container with sshd and a reachable IP |
| Can Ansible configure an instance? | No, so the CONFIGURING phase and the end-to-end job cannot run | Yes, the full PLANNING → APPLYING → CONFIGURING → READY job runs locally |
| IAM enforcement | None: its own issue tracker records that no IAM authorization is evaluated | None |
| EC2 networking APIs this project's Terraform calls | Recently stubs (`CreateSubnet`, `AttachInternetGateway`, `DescribeKeyPairs`, `RevokeSecurityGroupEgress`); the current source no longer marks them as stubs, but releases are daily and the list moves fast | Work (see the table above) |
| Breadth | Very wide: hundreds of services, with fidelity scores published per release | Narrower, covers what this project uses |
| Endpoint | HTTPS on `:9297` with a self-signed certificate that Terraform and the SDK must trust | HTTP on `:4566` |
| Other | Usage telemetry on by default (`--telemetry off`); does not take pull requests | Open source (MIT) |

Decision: Floci stays the emulator for development and CI, because only it can run the
Ansible half of the job. AWS Emulator may be useful later as a second check for API-only
services that Floci covers less well, such as the DynamoDB audit publisher or the Track F
services (CloudTrail, Config, KMS). Neither emulator proves IAM, so IAM is verified on real AWS.
