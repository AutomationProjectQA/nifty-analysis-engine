#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# ==========================================
# CONFIGURATION - Adjust these paths if needed
# ==========================================
DEPLOY_JAR_DIR="/var/nifty"
DEPLOY_JAR_PATH="${DEPLOY_JAR_DIR}/nifty-engine.jar"
NGINX_WEB_ROOT="/var/www/nifty-portal/dist"
# ==========================================

echo "=========================================="
echo "🚀 Starting Nifty Portal Deployment Script"
echo "=========================================="

# 1. Fetch latest changes
echo "📥 Pulling latest git repository updates..."
git pull

# 2. Package Java Backend
echo "☕ Building Spring Boot backend jar..."
mvn clean package -DskipTests

# 3. Deploy Backend Jar and Restart Systemd Service
echo "⚙️ Deploying backend jar and restarting service..."
if [ ! -d "${DEPLOY_JAR_DIR}" ]; then
    echo "Creating deployment directory: ${DEPLOY_JAR_DIR}"
    sudo mkdir -p "${DEPLOY_JAR_DIR}"
    sudo chown -R $USER:$USER "${DEPLOY_JAR_DIR}"
fi

# Stop systemd service
echo "Stopping nifty-engine service..."
sudo systemctl stop nifty-engine || true

# Copy compiled jar
echo "Copying jar to ${DEPLOY_JAR_PATH}..."
sudo cp target/nifty-analysis-engine-1.0.0-SNAPSHOT.jar "${DEPLOY_JAR_PATH}"

# Start service
echo "Starting nifty-engine service..."
sudo systemctl start nifty-engine
echo "Backend service restarted."

# 4. Package React Frontend
echo "📦 Installing and building React frontend assets..."
cd frontend
npm install
npm run build

# 5. Deploy Frontend Assets to Nginx Web Root
echo "🖥️ Deploying static assets to Nginx web root: ${NGINX_WEB_ROOT}..."
if [ ! -d "${NGINX_WEB_ROOT}" ]; then
    sudo mkdir -p "${NGINX_WEB_ROOT}"
fi

# Clear old assets and copy new ones
sudo rm -rf "${NGINX_WEB_ROOT}"/*
sudo cp -r dist/* "${NGINX_WEB_ROOT}/"

# 6. Reload Nginx Config
echo "🔄 Reloading Nginx configuration..."
sudo systemctl reload nginx

echo "=========================================="
echo "✅ Deployment Successful!"
echo "Backend service: systemctl status nifty-engine"
echo "Frontend URL: https://niftyintel.com"
echo "=========================================="
cd ..
