#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-localhost:9090}"
SERVICE="dev.executor.common.ShellService"

echo "=== StartJob ==="
response=$(grpcurl -plaintext -d '{"command": "sleep 2; echo hello"}' "$HOST" "$SERVICE/StartJob")
echo "$response" | jq .

job_id=$(echo "$response" | jq -r '.job_id')
echo "Job ID: $job_id"

echo
echo "=== GetJobStatus (running) ==="
status=$(grpcurl -plaintext -d "{\"job_id\": \"$job_id\"}" "$HOST" "$SERVICE/GetJobStatus")
echo "$status" | jq .

echo
echo "Waiting 3 seconds for container to finish..."
sleep 3

echo "=== GetJobStatus (completed) ==="
status=$(grpcurl -plaintext -d "{\"job_id\": \"$job_id\"}" "$HOST" "$SERVICE/GetJobStatus")
echo "$status" | jq .
