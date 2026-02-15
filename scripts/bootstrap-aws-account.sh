#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <ACCOUNT_ID> <REGION>"
    echo "Example: $0 123456789012 us-east-1"
    exit 1
fi

ACCOUNT_ID=$1
REGION=$2

CURRENT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
if [ "$CURRENT_ACCOUNT" != "$ACCOUNT_ID" ]; then
    echo "❌ Error: Your currently authenticated AWS Account ($CURRENT_ACCOUNT) does not match the target Account ($ACCOUNT_ID)."
    echo "Please configure your AWS credentials to target account $ACCOUNT_ID."
    exit 1
fi

STACK_NAME="remote-executor-pipeline"
TEMPLATE_FILE="iac/pipeline.yaml"

REPO_NAME="atomicul/remote-executor"

if [ -z "${CODESTAR_ARN:-}" ]; then
    echo "❌ Error: CODESTAR_ARN environment variable is not set."
    echo "Please create a CodeStar connection in AWS and run: export CODESTAR_ARN=\"arn:aws:...\""
    exit 1
fi

echo "====================================================="
echo " Bootstrapping AWS CI/CD Pipeline"
echo " Account:    $ACCOUNT_ID"
echo " Region:     $REGION"
echo " Stack Name: $STACK_NAME"
echo "====================================================="

aws cloudformation deploy \
  --region "$REGION" \
  --template-file "$TEMPLATE_FILE" \
  --stack-name "$STACK_NAME" \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
  --no-fail-on-empty-changeset \
  --tags "Project=RemoteExecutor" "ManagedBy=CloudFormation" \
  --parameter-overrides \
      RepositoryName="$REPO_NAME" \
      BranchName="main" \
      CodeStarConnectionArn="$CODESTAR_ARN"

echo ""
echo "✅ Bootstrap complete! Everything, including the Image Builder Pipeline, is deployed."
