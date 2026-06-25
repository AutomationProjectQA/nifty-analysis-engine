#!/usr/bin/env bash
# Loads secrets from .env and starts the Spring Boot backend.
# Usage: ./run-local.sh
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Copy .env.example to .env and fill in your secrets." >&2
  exit 1
fi

# Export every non-comment line from .env into the environment
set -a
# shellcheck disable=SC1091
source .env
set +a

echo "Environment loaded from .env. Starting backend..."
exec mvn spring-boot:run
