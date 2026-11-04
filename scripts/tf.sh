#!/usr/bin/env bash
# Run Terraform for one layer and target, with the matching backend configuration.
#
# Usage:
#   scripts/tf.sh <bootstrap|platform> <floci|aws> <terraform command> [args...]
#   ENVIRONMENT_ID=<uuid> scripts/tf.sh environment <floci|aws> <command> [args...]
#
# Examples:
#   scripts/tf.sh bootstrap floci apply
#   scripts/tf.sh platform floci plan
#   scripts/tf.sh platform floci output -raw vpc_id
#
# Why a wrapper: a backend block cannot use variables, so the target's backend comes
# from <target>.s3.tfbackend at init time. Mixing them up is dangerous (target=aws
# recorded in the emulator's state means real, billed resources nobody tracks). This
# script always pairs the backend with the same target, and keeps each target's
# initialized backend in its own data directory (.terraform/<target>), so switching
# target never reuses the other one.
set -euo pipefail

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

[[ $# -ge 3 ]] || usage
layer=$1
target=$2
shift 2

case $target in
  floci | aws) ;;
  *) usage ;;
esac

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root/terraform/$layer" 2>/dev/null || usage

backend_args=()
case $layer in
  bootstrap)
    export TF_DATA_DIR=.terraform/$target
    backend_args=(-backend-config="path=terraform.$target.tfstate")
    ;;
  platform)
    export TF_DATA_DIR=.terraform/$target
    backend_args=(-backend-config="$target.s3.tfbackend")
    ;;
  environment)
    : "${ENVIRONMENT_ID:?set ENVIRONMENT_ID to the environment UUID}"
    export TF_DATA_DIR=.terraform/$target-$ENVIRONMENT_ID
    backend_args=(
      -backend-config="$target.s3.tfbackend"
      -backend-config="key=environments/$ENVIRONMENT_ID/terraform.tfstate"
    )
    ;;
  *) usage ;;
esac

if [[ $layer != bootstrap && ! -f $target.s3.tfbackend ]]; then
  echo "Missing terraform/$layer/$target.s3.tfbackend (copy it from $target.s3.tfbackend.example)." >&2
  exit 1
fi

var_args=(-var "target=$target")
[[ $layer == environment ]] && var_args+=(-var "environment_id=$ENVIRONMENT_ID")
[[ -f $target.tfvars ]] && var_args+=(-var-file="$target.tfvars")

command=$1
shift

# First use of this layer/target: initialize its backend automatically.
# Provider versions stay pinned by the committed .terraform.lock.hcl.
if [[ $command != init && ! -d $TF_DATA_DIR ]]; then
  terraform init -input=false "${backend_args[@]}" >&2
fi

case $command in
  init) exec terraform init -input=false "${backend_args[@]}" "$@" ;;
  plan | apply | destroy | refresh | import | console) exec terraform "$command" "${var_args[@]}" "$@" ;;
  *) exec terraform "$command" "$@" ;;
esac
