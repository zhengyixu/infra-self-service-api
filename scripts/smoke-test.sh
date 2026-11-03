#!/usr/bin/env bash
# Smoke test: verifies the AWS CLI can talk to the local Floci emulator.
# Usage: docker compose up -d && ./scripts/smoke-test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=floci-env.sh
source "${SCRIPT_DIR}/floci-env.sh"

SUFFIX="smoke-$(date +%s)"

step() { printf '\n==> %s\n' "$*"; }

step "Waiting for Floci at ${AWS_ENDPOINT_URL}"
for _ in $(seq 1 30); do
  aws sts get-caller-identity >/dev/null 2>&1 && break
  sleep 1
done

step "STS: caller identity"
aws sts get-caller-identity --query Account --output text

step "S3: create, write, read, delete"
BUCKET="${SUFFIX}"
aws s3 mb "s3://${BUCKET}" >/dev/null
echo "hello floci" | aws s3 cp - "s3://${BUCKET}/hello.txt" >/dev/null
[[ "$(aws s3 cp "s3://${BUCKET}/hello.txt" -)" == "hello floci" ]]
aws s3 rb "s3://${BUCKET}" --force >/dev/null
echo "ok"

step "DynamoDB: create table, put item, get item, delete table"
TABLE="${SUFFIX}"
aws dynamodb create-table --table-name "$TABLE" \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

cleanup() {
  aws dynamodb delete-table --table-name "$TABLE" >/dev/null 2>&1 || true
}

trap cleanup EXIT

aws dynamodb wait table-exists --table-name "$TABLE"

aws dynamodb put-item --table-name "$TABLE" \
  --item '{"id": {"S": "env-1"}, "status": {"S": "REQUESTED"}}'

STATUS="$(aws dynamodb get-item --table-name "$TABLE" \
  --key '{"id": {"S": "env-1"}}' \
  --consistent-read \
  --query 'Item.status.S' \
  --output text)"
if [[ "$STATUS" != "REQUESTED" ]]; then
  echo "FAILURE: expected status REQUESTED, got '${STATUS}'" >&2
  exit 1
fi
echo "ok"

step "EC2: default VPC and AMIs are visible"
aws ec2 describe-vpcs --query 'length(Vpcs)' --output text
aws ec2 describe-images --query 'length(Images)' --output text

printf '\nSmoke test passed.\n'
