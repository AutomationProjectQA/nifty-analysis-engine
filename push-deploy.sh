#!/usr/bin/env bash
#
# Run THIS on your laptop. Builds the jar + frontend LOCALLY, uploads them to the VM,
# and applies (swap jar + frontend, restart) in one shot. The VM builds nothing.
#
# One-time: set your VM target (user@ip or an ssh alias):
#   export NIFTY_VM=meet@YOUR_VM_IP
# Then every deploy is just:
#   ./push-deploy.sh
#
# Options:
#   SKIP_ENV=1 ./push-deploy.sh    # don't push local .env to the VM
#   NIFTY_SSH_OPTS="-i ~/.ssh/key" ./push-deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

VM="${NIFTY_VM:-}"
SSH_OPTS="${NIFTY_SSH_OPTS:-}"
STAGING="nifty-staging"

if [ -z "$VM" ]; then
  echo "ERROR: set your VM target first:  export NIFTY_VM=meet@YOUR_VM_IP"
  exit 1
fi

echo "== 1/4  Build backend jar (local) =="
mvn -q clean package -DskipTests
JAR="$(ls target/*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1)"
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "ERROR: jar not found in target/"; exit 1; }
echo "  $JAR"

echo "== 2/4  Build frontend (local) =="
( cd frontend && npm ci && npm run build )
tar -czf /tmp/nifty-dist.tgz -C frontend/dist .

echo "== 3/4  Upload artifacts to $VM =="
# shellcheck disable=SC2086
ssh $SSH_OPTS "$VM" "mkdir -p ~/$STAGING"
# shellcheck disable=SC2086
scp $SSH_OPTS "$JAR"               "$VM:~/$STAGING/app.jar"
# shellcheck disable=SC2086
scp $SSH_OPTS /tmp/nifty-dist.tgz  "$VM:~/$STAGING/dist.tgz"
# shellcheck disable=SC2086
scp $SSH_OPTS deploy-apply.sh      "$VM:~/$STAGING/apply.sh"
if [ -z "${SKIP_ENV:-}" ] && [ -f .env ]; then
  # shellcheck disable=SC2086
  scp $SSH_OPTS .env "$VM:~/$STAGING/nifty.env"
  echo "  (pushed local .env -> VM /etc/nifty/nifty.env)"
fi

echo "== 4/4  Apply on VM (you may be asked for the sudo password once) =="
# shellcheck disable=SC2086
ssh $SSH_OPTS -t "$VM" "sudo bash ~/$STAGING/apply.sh"

echo
echo "DEPLOYED. Check: https://niftyintel.com   (logs on VM: journalctl -u nifty-engine -f)"
