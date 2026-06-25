#!/usr/bin/env bash
#
# Runs ON THE VM (as root, via `sudo bash`). Swaps in the pre-built jar + frontend
# uploaded by push-deploy.sh and restarts the services. Builds nothing.
set -euo pipefail

STAGING="$(cd "$(dirname "$0")" && pwd)"
JAR_DST="/var/nifty/nifty-engine.jar"
WEB="/var/www/nifty-portal/dist"
ENV_DST="/etc/nifty/nifty.env"

echo "[apply] running as $(whoami) from $STAGING"

# 1. Sync env/secrets if uploaded (systemd reads this via EnvironmentFile)
if [ -f "$STAGING/nifty.env" ]; then
  mkdir -p /etc/nifty
  cp "$STAGING/nifty.env" "$ENV_DST"
  chmod 600 "$ENV_DST"
  echo "[apply] env -> $ENV_DST"
fi

# 2. Backend: swap jar + restart service
mkdir -p "$(dirname "$JAR_DST")"
echo "[apply] stopping nifty-engine..."
systemctl stop nifty-engine || true
cp "$STAGING/app.jar" "$JAR_DST"
echo "[apply] starting nifty-engine..."
systemctl start nifty-engine

# 3. Frontend: replace static assets
mkdir -p "$WEB"
rm -rf "${WEB:?}/"*
tar -xzf "$STAGING/dist.tgz" -C "$WEB"
echo "[apply] frontend updated -> $WEB"

# 4. Reload nginx
nginx -t && systemctl reload nginx
echo "[apply] nginx reloaded"

echo "[apply] backend status:"
systemctl --no-pager status nifty-engine | head -6 || true
echo "[apply] DONE"
