#!/usr/bin/env bash

# Exit immediately if a command exits with a non-zero status
set -e

# ==========================================
# CONFIGURATION - Adjust these paths if needed
# ==========================================
DEPLOY_JAR_DIR="/var/nifty"
DEPLOY_JAR_PATH="${DEPLOY_JAR_DIR}/nifty-engine.jar"
NGINX_WEB_ROOT="/var/www/nifty-portal/dist"

# Find the uploaded jar in the home directory or current directory
UPLOADED_JAR="$HOME/nifty-analysis-engine-1.0.0-SNAPSHOT.jar"
# Fallback to current directory if not found in home
if [ ! -f "${UPLOADED_JAR}" ]; then
    UPLOADED_JAR="./nifty-analysis-engine-1.0.0-SNAPSHOT.jar"
fi
# ==========================================

echo "=========================================="
echo "🚀 Deploying Uploaded Jar & Updating Frontend"
echo "=========================================="

# Check if the uploaded jar exists
if [ ! -f "${UPLOADED_JAR}" ]; then
    echo "❌ Error: Pre-built jar file not found at ${UPLOADED_JAR}"
    echo "Please make sure your jar file is uploaded completely."
    exit 1
fi

echo "📦 Found uploaded jar at: ${UPLOADED_JAR}"

# 1. Ensure deployment folder exists
if [ ! -d "${DEPLOY_JAR_DIR}" ]; then
    echo "Creating deployment directory: ${DEPLOY_JAR_DIR}"
    sudo mkdir -p "${DEPLOY_JAR_DIR}"
    sudo chown -R $USER:$USER "${DEPLOY_JAR_DIR}"
fi

# 2. Deploy Backend Jar and Restart Systemd Service
echo "⚙️ Stopping nifty-engine service..."
sudo systemctl stop nifty-engine || true

echo "Copying jar to ${DEPLOY_JAR_PATH}..."
sudo cp "${UPLOADED_JAR}" "${DEPLOY_JAR_PATH}"
sudo chmod +x "${DEPLOY_JAR_PATH}"

echo "Starting nifty-engine service..."
sudo systemctl start nifty-engine
echo "Backend service restarted."

# 3. Package React Frontend
echo "📦 Installing and building React frontend assets..."
cd frontend
npm install
npm run build

# 4. Deploy Frontend Assets to Nginx Web Root
echo "🖥️ Deploying static assets to Nginx web root: ${NGINX_WEB_ROOT}..."
if [ ! -d "${NGINX_WEB_ROOT}" ]; then
    sudo mkdir -p "${NGINX_WEB_ROOT}"
fi

# Clear old assets and copy new ones
sudo rm -rf "${NGINX_WEB_ROOT}"/*
sudo cp -r dist/* "${NGINX_WEB_ROOT}/"

# 5. Reload Nginx Config
echo "🔄 Reloading Nginx configuration..."
sudo systemctl reload nginx

echo "=========================================="
echo "✅ Deployment of Pre-built Jar Successful!"
echo "Backend service: systemctl status nifty-engine"
echo "Frontend URL: https://niftyintel.com"
echo "=========================================="
cd ..
