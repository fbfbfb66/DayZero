#!/bin/bash
# Deploy hook for Let's Encrypt renewal.
# Place at:
#   /etc/letsencrypt/renewal-hooks/deploy/01-dayzero-nginx-cert
# Make executable:
#   chmod +x /etc/letsencrypt/renewal-hooks/deploy/01-dayzero-nginx-cert
#
# certbot runs every executable in renewal-hooks/deploy/ after a successful
# renewal and sets RENEWED_LINEAGE to the cert's live directory.
#
# What this hook does, in order:
#   1. Atomically copies the renewed fullchain/privkey into the Nginx SSL
#      directory (write temp file in the same directory, then mv). The
#      current certificate is never truncated in place.
#   2. Locks the private key down to 600.
#   3. Validates the production Nginx configuration with `nginx -t`.
#   4. Reloads Nginx only if the syntax check passes, so a bad state cannot
#      take the public entrypoint down.
#
# This hook never runs on a failed renewal and never modifies the Let's
# Encrypt lineage itself.

set -euo pipefail

NGINX_CONTAINER="dayzero-ai-nginx"
SSL_DIR="/opt/dayzero-ai/nginx/ssl"
LINEAGE="${RENEWED_LINEAGE:-/etc/letsencrypt/live/api.dayzero.cn}"

echo "[certbot-deploy] Renewed lineage: ${LINEAGE}"

if [[ ! -f "${LINEAGE}/fullchain.pem" || ! -f "${LINEAGE}/privkey.pem" ]]; then
    echo "[certbot-deploy] ERROR: renewed cert files missing under ${LINEAGE}" >&2
    exit 1
fi

echo "[certbot-deploy] Atomically installing renewed certificate into ${SSL_DIR}..."
cp "${LINEAGE}/fullchain.pem" "${SSL_DIR}/.fullchain.pem.new"
cp "${LINEAGE}/privkey.pem" "${SSL_DIR}/.privkey.pem.new"
chmod 644 "${SSL_DIR}/.fullchain.pem.new"
chmod 600 "${SSL_DIR}/.privkey.pem.new"
mv -f "${SSL_DIR}/.fullchain.pem.new" "${SSL_DIR}/fullchain.pem"
mv -f "${SSL_DIR}/.privkey.pem.new" "${SSL_DIR}/privkey.pem"

echo "[certbot-deploy] Testing Nginx configuration..."
docker exec "${NGINX_CONTAINER}" nginx -t

echo "[certbot-deploy] Reloading Nginx to pick up renewed certificate..."
docker exec "${NGINX_CONTAINER}" nginx -s reload

echo "[certbot-deploy] Reload complete."
