# Deployment Guide - Nifty Intelligence Portal (GCP VM & systemd)

This document describes how to deploy and update the Nifty options engine and frontend on your GCP VM using the native **systemd service (`nifty-engine`)** and **Nginx**.

---

## 1. Initial Infrastructure Setup

Run the following commands on your GCP VM to set up required dependencies (Java 21, Maven, Nginx, PostgreSQL, Redis, and Certbot):

```bash
# Update repositories
sudo apt update && sudo apt upgrade -y

# Install Java 21 & Maven
sudo apt install openjdk-21-jdk maven -y

# Install Nginx
sudo apt install nginx -y

# Install PostgreSQL (if running natively on the VM)
sudo apt install postgresql postgresql-contrib -y
sudo systemctl enable --now postgresql

# Install Redis (if running natively on the VM)
sudo apt install redis-server -y
sudo systemctl enable --now redis-server

# Install Certbot (for SSL/HTTPS certificates)
sudo apt install certbot python3-certbot-nginx -y
```

---

## 2. Systemd Service Configuration (`nifty-engine`)

Create a systemd configuration file to manage the Spring Boot backend process.

1. Open a new service configuration:
   ```bash
   sudo nano /etc/systemd/system/nifty-engine.service
   ```
2. Paste the following configuration, adjusting user and jar path mappings as needed:
   ```ini
   [Unit]
   Description=Nifty Option Analysis Engine Backend
   After=syslog.target network.target postgresql.service redis-server.service

   [Service]
   User=meet
   Type=simple
   WorkingDirectory=/var/nifty
   ExecStart=/usr/bin/java -jar /var/nifty/nifty-engine.jar
   SuccessExitStatus=143
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable nifty-engine
   ```

---

## 3. Nginx Domain Configuration

1. Create a custom Nginx site configuration mapping your purchased domain `niftyintel.com`:
   ```bash
   sudo nano /etc/nginx/sites-available/nifty-portal
   ```
2. Paste the contents of your [nginx.conf](file:///Users/meet/Documents/GitHub/nifty-analysis-engine/nginx.conf):
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
   }
   ```
3. Enable configuration and link to `sites-enabled`:
   ```bash
   sudo ln -sf /etc/nginx/sites-available/nifty-portal /etc/nginx/sites-enabled/default
   sudo nginx -t
   sudo systemctl restart nginx
   ```

---

## 4. Deploying Latest Code Updates (Daily Workflow)

Run these commands on the VM to deploy new updates for backend features and frontend dashboard tweaks:

### Step 1: Pull Latest Repository Changes
```bash
git pull
```

### Step 2: Build and Update the Backend
1. Build the production package jar:
   ```bash
   mvn clean package -DskipTests
   ```
2. Stop the running service and overwrite the active jar:
   ```bash
   sudo systemctl stop nifty-engine
   sudo cp target/nifty-analysis-engine-1.0.0-SNAPSHOT.jar /var/nifty/nifty-engine.jar
   ```
3. Restart the service:
   ```bash
   sudo systemctl start nifty-engine
   ```

### Step 3: Build and Update the Frontend
1. Navigate to the frontend folder and compile:
   ```bash
   cd frontend
   npm install
   npm run build
   ```
2. Empty old files and copy the new production bundle:
   ```bash
   sudo rm -rf /var/www/nifty-portal/dist/*
   sudo cp -r dist/* /var/www/nifty-portal/dist/
   ```
3. Reload Nginx configuration:
   ```bash
   sudo systemctl reload nginx
   ```

---

## 5. Setting up SSL via Certbot (Run Once)
To generate trusted HTTPS SSL certificates for your domain:
```bash
sudo certbot --nginx -d niftyintel.com -d www.niftyintel.com
sudo systemctl restart nginx
```
