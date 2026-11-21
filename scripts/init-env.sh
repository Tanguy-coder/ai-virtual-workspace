#!/usr/bin/env bash
# Generates a local .env with random secrets. Never overwrites an existing .env.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo ".env already exists, leaving it untouched."
  exit 0
fi
umask 077
cat > .env <<EOF
AIW_DB_PASSWORD=$(openssl rand -hex 24)
# 32-byte key, base64. Encrypts provider API keys and project secrets at rest.
# Losing it makes stored secrets unreadable.
AIW_MASTER_KEY=$(openssl rand -base64 32)
# Bearer token Prometheus uses to read the platform's metrics (docs/18).
AIW_METRICS_TOKEN=$(openssl rand -hex 24)
AIW_GRAFANA_PASSWORD=$(openssl rand -hex 12)
EOF
echo ".env created."
