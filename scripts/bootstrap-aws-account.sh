#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "Usage: $0 <ACCOUNT_ID> <REGION> [pipeline|executor|dynamodb|scheduler|scheduler-pipeline]"
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

    local current_version
    current_version=$(aws imagebuilder list-components \
        --owner Self \
        --filters "name=name,values=remote-executor-setup" \
        --region "$REGION" \
        --query 'componentVersionList[].version' \
        --output text 2>/dev/null \
        | tr '\t' '\n' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1) || true

    if [ -z "$current_version" ]; then
        current_version="0.0.0"
    fi

    local major minor patch
    IFS='.' read -r major minor patch <<< "$current_version"
    local next_version="${major}.${minor}.$((patch + 1))"

    echo "Deploying stack: $stack_name (ImageVersion: $current_version → $next_version)"

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
          CodeStarConnectionArn="$CODESTAR_ARN" \
          ImageVersion="$next_version"

    echo "✅ Stack $stack_name deployed."
}

deploy_scheduler_pipeline() {
    if [ -z "${CODESTAR_ARN:-}" ]; then
        echo "❌ Error: CODESTAR_ARN environment variable is not set."
        echo "Please create a CodeStar connection in AWS and run: export CODESTAR_ARN=\"arn:aws:...\""
        exit 1
    fi

    local stack_name="remote-executor-scheduler-pipeline"

    local current_version
    current_version=$(aws imagebuilder list-components \
        --owner Self \
        --filters "name=name,values=remote-executor-scheduler-setup" \
        --region "$REGION" \
        --query 'componentVersionList[].version' \
        --output text 2>/dev/null \
        | tr '\t' '\n' | sort -t. -k1,1n -k2,2n -k3,3n | tail -1) || true

    if [ -z "$current_version" ]; then
        current_version="0.0.0"
    fi

    local major minor patch
    IFS='.' read -r major minor patch <<< "$current_version"
    local next_version="${major}.${minor}.$((patch + 1))"

    echo "Deploying stack: $stack_name (ImageVersion: $current_version → $next_version)"

    aws cloudformation deploy \
      --region "$REGION" \
      --template-file iac/scheduler-pipeline.yaml \
      --stack-name "$stack_name" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
      --no-fail-on-empty-changeset \
      --tags "Project=RemoteExecutor" "ManagedBy=CloudFormation" \
      --parameter-overrides \
          RepositoryName="atomicul/remote-executor" \
          BranchName="main" \
          CodeStarConnectionArn="$CODESTAR_ARN" \
          ImageVersion="$next_version"

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

deploy_scheduler() {
    if [ -z "${PROVISIONER_VERSION:-}" ] || [ -z "${SUBMITTER_VERSION:-}" ]; then
        echo "❌ Error: PROVISIONER_VERSION and SUBMITTER_VERSION environment variables must be set."
        echo "Example: export PROVISIONER_VERSION=1 SUBMITTER_VERSION=1"
        exit 1
    fi

    local stack_name="remote-executor-scheduler"
    echo "Deploying stack: $stack_name"

    local artifact_bucket
    artifact_bucket=$(aws cloudformation describe-stacks \
        --stack-name remote-executor-scheduler-pipeline \
        --query 'Stacks[0].Outputs[?OutputKey==`ArtifactBucketName`].OutputValue' \
        --output text --region "$REGION")

    aws cloudformation deploy \
      --region "$REGION" \
      --template-file iac/scheduler.yaml \
      --stack-name "$stack_name" \
      --capabilities CAPABILITY_IAM \
      --no-fail-on-empty-changeset \
      --tags "Project=RemoteExecutor" "ManagedBy=CloudFormation" \
      --parameter-overrides \
          LambdaCodeBucket="$artifact_bucket" \
          LambdaCodeKey="remote-executor/scale-out.jar" \
          ProvisionerVersion="$PROVISIONER_VERSION" \
          SubmitterVersion="$SUBMITTER_VERSION"

    echo "✅ Stack $stack_name deployed."
}

deploy_dynamodb() {
    local stack_name="remote-executor-dynamodb"
    echo "Deploying stack: $stack_name"

    aws cloudformation deploy \
      --region "$REGION" \
      --template-file iac/dynamodb.yaml \
      --stack-name "$stack_name" \
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
    dynamodb)
        deploy_dynamodb
        ;;
    scheduler)
        deploy_scheduler
        ;;
    scheduler-pipeline)
        deploy_scheduler_pipeline
        ;;
    all)
        deploy_pipeline & pid1=$!
        deploy_executor & pid2=$!
        deploy_dynamodb & pid3=$!
        deploy_scheduler_pipeline & pid4=$!
        deploy_scheduler & pid5=$!
        fail=0
        wait "$pid1" || fail=1
        wait "$pid2" || fail=1
        wait "$pid3" || fail=1
        wait "$pid4" || fail=1
        wait "$pid5" || fail=1
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
        echo "Valid options: pipeline, executor, dynamodb, scheduler, scheduler-pipeline"
        exit 1
        ;;
esac
