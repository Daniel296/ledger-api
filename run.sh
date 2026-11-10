#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Building and starting the service"
docker compose -f docker/docker-compose.yml up --build --detach --wait

echo "==> Running the tests"
./mvnw -B verify

echo "==> Ledger API is running on http://localhost:8080 (stop it with: docker compose -f docker/docker-compose.yml down)"
