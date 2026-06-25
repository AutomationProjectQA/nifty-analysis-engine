# Quick Deploy — build local, push, one command

The VM **builds nothing** (no Maven, no npm) — that's what made it slow/hard before.
You build on your laptop and push the finished artifacts.

---

## One-time setup (do once on the VM)

```bash
# 1. systemd unit that reads secrets from an env file (REQUIRED — the app uses ${...} placeholders now)
sudo cp nifty-engine.service /etc/systemd/system/nifty-engine.service   # or paste it
sudo mkdir -p /etc/nifty /var/nifty /var/www/nifty-portal/dist
sudo systemctl daemon-reload
sudo systemctl enable nifty-engine
```

That's it. The env file (`/etc/nifty/nifty.env`) gets created automatically on your first deploy
from your local `.env`.

> Make sure your laptop can SSH to the VM (`ssh meet@YOUR_VM_IP` works), and that your VM
> has Postgres + Redis running (see DEPLOY.md section 1 if not).

---

## Every deploy (one command, from your laptop)

```bash
export NIFTY_VM=meet@YOUR_VM_IP     # set once per shell (or add to ~/.zshrc)
./push-deploy.sh
```

What it does:
1. Builds the jar (`mvn package`) and the frontend (`npm run build`) **locally**
2. Uploads `app.jar`, `dist.tgz`, the apply script, and your `.env` to `~/nifty-staging` on the VM
3. Runs `deploy-apply.sh` as root on the VM → swaps the jar, restarts `nifty-engine`,
   replaces the frontend in the nginx web root, reloads nginx

You'll be asked for the VM sudo password **once**.

### Useful flags
```bash
SKIP_ENV=1 ./push-deploy.sh                 # don't overwrite the VM's /etc/nifty/nifty.env
NIFTY_SSH_OPTS="-i ~/.ssh/mykey" ./push-deploy.sh
```

> Using `gcloud compute ssh`? Add a host alias to `~/.ssh/config` (run
> `gcloud compute config-ssh`), then set `NIFTY_VM=<that-alias>`.

---

## Verify after deploy
```bash
# on the VM
journalctl -u nifty-engine -f          # watch backend logs
curl -s localhost:8080/api/v1/health   # {"status":"UP",...}
```
Open https://niftyintel.com — the frontend should be the new build.

---

## Config / secrets
Your **local `.env` is the single source of truth**. Edit it, run `./push-deploy.sh`, and the
new values land in `/etc/nifty/nifty.env` and take effect on restart. (Set `SKIP_ENV=1` if you
manage the VM's env file by hand.)

`.env` is gitignored — secrets never go in the repo. Rotate the previously-committed creds before going live.
