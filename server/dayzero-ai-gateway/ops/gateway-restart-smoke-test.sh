#!/bin/bash
# Restart or recreate the Gateway and verify automatic recovery WITHOUT any
# manual Nginx reload. Run on the ECS host as root or a user in the docker
# group.
#
# Usage:
#   ./gateway-restart-smoke-test.sh [restart|recreate]
#
#   restart  (default) docker compose restart dayzero-ai-gateway
#   recreate            docker compose up -d --no-deps --force-recreate dayzero-ai-gateway
#
# Expected result: after the Gateway is healthy again, the public HTTPS
# endpoints recover on their own (Nginx resolves the new container IP via
# Docker DNS 127.0.0.11), anonymous AI requests keep returning 401, and no
# sustained 502 is observed.

set -euo pipefail

MODE="${1:-restart}"
COMPOSE_FILE="/opt/dayzero-ai/docker-compose.production.yml"
ENV_FILE="/opt/dayzero-ai/.env.production"
PUBLIC_HOST="https://api.dayzero.cn"
MAX_WAIT_SECONDS=120

log() {
    echo "[gateway-restart-smoke] $*"
}

wait_for_public() {
    local url="$1"
    local expected="$2"
    local elapsed=0
    while true; do
        local code
        code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 5 "${url}" || echo "000")
        if [[ "${code}" == "${expected}" ]]; then
            log "${url} -> ${code} (OK, recovered after ${elapsed}s)"
            return 0
        fi
        if (( elapsed >= MAX_WAIT_SECONDS )); then
            log "TIMEOUT: ${url} still returns ${code} after ${MAX_WAIT_SECONDS}s (sustained failure)"
            return 1
        fi
        log "waiting... ${url} returned ${code}"
        sleep 5
        elapsed=$((elapsed + 5))
    done
}

case "${MODE}" in
    restart)
        log "Restarting Gateway container..."
        docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" restart dayzero-ai-gateway
        ;;
    recreate)
        log "Recreating Gateway container (new container IP)..."
        docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --no-deps --force-recreate dayzero-ai-gateway
        ;;
    *)
        echo "Usage: $0 [restart|recreate]" >&2
        exit 2
        ;;
esac

log "Mode=${MODE}. Waiting for public /health and /ready to recover (no Nginx reload)..."
wait_for_public "${PUBLIC_HOST}/health" "200"
wait_for_public "${PUBLIC_HOST}/ready" "200"

log "Verifying anonymous AI requests are still rejected with 401..."
code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 \
    -X POST "${PUBLIC_HOST}/api/ai/assistant-turn-v2" \
    -H "Content-Type: application/json" \
    -d '{}' || echo "000")
if [[ "${code}" != "401" ]]; then
    log "FAIL: expected 401 from /api/ai/assistant-turn-v2, got ${code}"
    exit 1
fi
log "Anonymous /api/ai/assistant-turn-v2 -> ${code} (OK)"

code=$(curl -sS -o /dev/null -w "%{http_code}" --max-time 15 \
    -X POST "${PUBLIC_HOST}/api/ai/assistant-turn-v2-stream" \
    -H "Content-Type: application/json" \
    -d '{}' || echo "000")
if [[ "${code}" != "401" ]]; then
    log "FAIL: expected 401 from /api/ai/assistant-turn-v2-stream, got ${code}"
    exit 1
fi
log "Anonymous /api/ai/assistant-turn-v2-stream -> ${code} (OK)"

log "All ${MODE}/recovery checks passed."
