#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-localhost:9090}"
SERVICE="dev.executor.common.ShellService"

echo "=== list ==="
grpcurl -plaintext "$HOST" list

echo "=== StartJob ==="
grpcurl -plaintext -d '{"command": "echo hello"}' "$HOST" "$SERVICE/StartJob"

echo "=== GetJobStatus ==="
grpcurl -plaintext -d '{"job_id": "nonexistent"}' "$HOST" "$SERVICE/GetJobStatus"
