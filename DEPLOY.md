# Deployment Guide — Nifty Intelligence Portal (GCP VM & systemd)

Deploy and update the Nifty engine + frontend on your GCP VM using the native
**systemd service (`nifty-engine`)** and **Nginx**.

**Model: build on your laptop, push finished artifacts to the VM.** The VM runs the jar
and serves the static frontend — it does **not** compile anything (no Maven, no npm on the
VM). This is what makes updates fast and avoids out-of-memory builds on small VMs.

> TL;DR for day-to-day updates is in [DEPLOY_QUICK.md](DEPLOY_QUICK.md). This file is the full reference.

---

## 1. Initial Infrastructure Setup (on the VM, once)

The VM only needs the Java **runtime** (to run the jar), Nginx, Postgres, Redis, and Certbot.
Maven and Node are **not** installed on the VM — building happens on your laptop.

```bash
sudo apt update && sudo apt upgrade -y

# Java 21 runtime (to RUN the jar — no Maven needed on the VM)
sudo apt install openjdk-21-jre-headless -y

# Nginx (serves the frontend + proxies /api to the backend)
sudo apt install nginx -y

# PostgreSQL + Redis (if running natively on the VM)
sudo apt install postgresql postgresql-contrib redis-server -y
sudo systemctl enable --now postgresql redis-server

# Certbot (HTTPS)
sudo apt install certbot python3-certbot-nginx -y

# Directories used by the deploy
sudo mkdir -p /var/nifty /var/www/nifty-portal/dist /etc/nifty
```

> On your **laptop** you need Java 21 + Maven + Node (to build), plus SSH access to the VM.

---

## 2. systemd Service (`nifty-engine`) — with EnvironmentFile

The backend reads its secrets/config (Telegram, Gemini, Angel One, DB) from environment
variables. **`EnvironmentFile` is required** — without it the app cannot resolve the
`${...}` placeholders in `application.yml` and will fail to start.

1. Install the unit (the repo ships [nifty-engine.service](nifty-engine.service)):
   ```bash
   sudo cp nifty-engine.service /etc/systemd/system/nifty-engine.service
   ```
   It contains:
   ```ini
   [Unit]
   Description=Nifty Option Analysis Engine Backend
   After=syslog.target network.target postgresql.service redis-server.service

   [Service]
   User=meet
   Type=simple
   WorkingDirectory=/var/nifty
   EnvironmentFile=/etc/nifty/nifty.env     # <-- REQUIRED (secrets/config)
   ExecStart=/usr/bin/java -jar /var/nifty/nifty-engine.jar
   SuccessExitStatus=143
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
2. Enable it:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable nifty-engine
   ```

The `/etc/nifty/nifty.env` file itself is created automatically on your first deploy
(`push-deploy.sh` uploads your local `.env`). See section 3.

---

## 3. Secrets / Config (`/etc/nifty/nifty.env`)

- Your **local `.env` is the single source of truth.** It is `KEY=value` lines
  (Telegram, Gemini, Angel One, `SPRING_DATASOURCE_*`, `SPRING_REDIS_*`) and is gitignored.
- `push-deploy.sh` uploads it to `/etc/nifty/nifty.env` (chmod 600) on every deploy, and
  systemd loads it via `EnvironmentFile`. Edit `.env`, redeploy, and the new values take
  effect on restart.
- Use `SKIP_ENV=1 ./push-deploy.sh` if you prefer to manage the VM's env file by hand.

> **Rotate the previously-committed credentials** (Angel One password/TOTP, Gemini key,
> Telegram token) before going live — they are in git history.

---

## 4. Nginx Domain Configuration

1. Create the site config (matches [nginx.conf](nginx.conf)):
   ```bash
   sudo nano /etc/nginx/sites-available/nifty-portal
   ```
   ```nginx
   server {
       listen 80;
       server_name niftyintel.com www.niftyintel.com;
       return 301 https://$host$request_uri;
   }

   server {
       listen 443 ssl;
       server_name niftyintel.com www.niftyintel.com;

       ssl_certificate /etc/letsencrypt/live/niftyintel.com/fullchain.pem;
       ssl_certificate_key /etc/letsencrypt/live/niftyintel.com/privkey.pem;

       ssl_protocols TLSv1.2 TLSv1.3;
       gzip on;
       gzip_types text/plain text/css application/json application/javascript text/xml;

       location / {
           root /var/www/nifty-portal/dist;
           index index.html;
           try_files $uri $uri/ /index.html;
       }

       location /api/ {
           proxy_pass http://localhost:8080/api/;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
       }

       # WebSocket / SockJS — live market-data streaming
       location /ws/ {
           proxy_pass http://localhost:8080/ws/;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection "upgrade";
           proxy_set_header Host $host;
           proxy_read_timeout 3600s;
           proxy_buffering off;
       }
   }
   ```
   > The frontend is built with `VITE_API_BASE_URL=` (empty) in `.env.production`, so it
   > calls `/api/...` and `/ws/...` on the **same origin** — which nginx proxies to
   > `localhost:8080`. The `/ws/` block (Upgrade headers) is **required** for live streaming;
   > without it the portal silently falls back to the initial REST snapshot only.
2. Enable + reload:
   ```bash
   sudo ln -sf /etc/nginx/sites-available/nifty-portal /etc/nginx/sites-enabled/default
   sudo nginx -t
   sudo systemctl restart nginx
   ```

---

## 5. SSL via Certbot (once)
```bash
sudo certbot --nginx -d niftyintel.com -d www.niftyintel.com
sudo systemctl restart nginx
```

---

## 6. Deploying Updates (the daily workflow)

### Recommended: one command from your laptop
```bash
export NIFTY_VM=meet@YOUR_VM_IP     # once per shell (or add to ~/.zshrc)
./push-deploy.sh
```
This builds the jar + frontend **locally**, uploads `app.jar`, `dist.tgz`, the apply script,
and your `.env` to `~/nifty-staging` on the VM, then runs [deploy-apply.sh](deploy-apply.sh)
as root to: swap the jar → restart `nifty-engine` → replace the frontend in the nginx web
root → reload nginx. You enter the VM sudo password **once**.

Options: `SKIP_ENV=1` (don't push `.env`), `NIFTY_SSH_OPTS="-i ~/.ssh/key"`.
Using `gcloud compute ssh`? Run `gcloud compute config-ssh` once, then set `NIFTY_VM` to the alias.

### Manual fallback (on the VM, if you ever need it)
Only if you can't push from your laptop. Requires Maven + Node installed on the VM.
```bash
git pull
mvn clean package -DskipTests
sudo systemctl stop nifty-engine
sudo cp target/nifty-analysis-engine-1.0.0-SNAPSHOT.jar /var/nifty/nifty-engine.jar
sudo systemctl start nifty-engine

cd frontend && npm ci && npm run build
sudo rm -rf /var/www/nifty-portal/dist/* && sudo cp -r dist/* /var/www/nifty-portal/dist/
sudo systemctl reload nginx
```

---

## 7. Verify & Troubleshoot
```bash
sudo systemctl status nifty-engine      # is it running?
journalctl -u nifty-engine -f           # live backend logs
curl -s localhost:8080/api/v1/health    # {"status":"UP", components:{database, redis, model, tradingEnabled}}
```
Open https://niftyintel.com — the header chip should read **"Live Connection"** when the
backend is reachable.

Common issues:
- **Backend won't start / `Could not resolve placeholder`** → `/etc/nifty/nifty.env` is
  missing or the unit lacks `EnvironmentFile`. Re-run `./push-deploy.sh` and confirm section 2.
- **`/health` returns 503** → database unreachable; check Postgres is running and `SPRING_DATASOURCE_*` in the env.
- **Frontend loads but no data** → check the nginx `/api/` proxy and that the backend is up.
