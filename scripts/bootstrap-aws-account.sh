#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <ACCOUNT_ID> <REGION> [pipeline|executor]"
    echo "Example: $0 123456789012 us-east-1"
    exit 1
fi

ACCOUNT_ID=$1
REGION=$2
STACK=${3:-all}

CURRENT_ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
if [ "$CURRENT_ACCOUNT" != "$ACCOUNT_ID" ]; then
    echo "❌ Error: Your currently authenticated AWS Account ($CURRENT_ACCOUNT) does not match the target Account ($ACCOUNT_ID)."
    echo "Please configure your AWS credentials to target account $ACCOUNT_ID."
    exit 1
fi

deploy_pipeline() {
    if [ -z "${CODESTAR_ARN:-}" ]; then
        echo "❌ Error: CODESTAR_ARN environment variable is not set."
        echo "Please create a CodeStar connection in AWS and run: export CODESTAR_ARN=\"arn:aws:...\""
        exit 1
    fi

    local stack_name="remote-executor-pipeline"
    echo "Deploying stack: $stack_name"

    aws cloudformation deploy \
      --region "$REGION" \
      --template-file iac/pipeline.yaml \
      --stack-name "$stack_name" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      --no-fail-on-empty-changeset \
      --tags "Project=RemoteExecutor" "ManagedBy=CloudFormation" \
      --parameter-overrides \
          RepositoryName="atomicul/remote-executor" \
          BranchName="main" \
          CodeStarConnectionArn="$CODESTAR_ARN"

    echo "✅ Stack $stack_name deployed."
}

deploy_executor() {
    local stack_name="remote-executor-executor"
    echo "Deploying stack: $stack_name"

    aws cloudformation deploy \
      --region "$REGION" \
      --template-file iac/executor.yaml \
      --stack-name "$stack_name" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      --no-fail-on-empty-changeset \
      --tags "Project=RemoteExecutor" "ManagedBy=CloudFormation"

    echo "✅ Stack $stack_name deployed."
}

echo "====================================================="
echo " Bootstrapping AWS Infrastructure"
echo " Account:    $ACCOUNT_ID"
echo " Region:     $REGION"
echo " Stack:      $STACK"
echo "====================================================="

case "$STACK" in
    pipeline)
        deploy_pipeline
        ;;
    executor)
        deploy_executor
        ;;
    all)
        deploy_pipeline & pid1=$!
        deploy_executor & pid2=$!
        fail=0
        wait "$pid1" || fail=1
        wait "$pid2" || fail=1
        if [ "$fail" -ne 0 ]; then
            echo ""
            echo "❌ One or more stacks failed to deploy."
            exit 1
        fi
        echo ""
        echo "✅ Bootstrap complete! All stacks deployed."
        ;;
    *)
        echo "❌ Unknown stack: $STACK"
        echo "Valid options: pipeline, executor"
        exit 1
        ;;
esac
