# Execution Guide - Nifty Intelligence Portal

This file outlines the exact steps and commands to start and execute the Nifty option analysis engine and frontend dashboard portal.

---

## Method A: Local Development Mode (Recommended for Testing)

Run the database services in Docker, and compile/run the Java backend and React frontend locally.

### 1. Start Databases (Postgres & Redis)
Ensure Docker Desktop is running on your machine, then execute:
```bash
docker compose up -d
```
*Port mappings: Postgres runs on `5432`, Redis runs on `6379`.*

### 2. Start Java Spring Boot Backend
In the project root folder, execute:
```bash
mvn spring-boot:run
```
*API Base URL: `http://localhost:8080`*

### 3. Start React Frontend
In a new terminal, navigate to the frontend directory and start the Vite dev server:
```bash
cd frontend
npm install
npm run dev
```
*The portal will open on: `http://localhost:3000`*

---

## Method B: Production Deployment Mode (VPS Hosting Setup)

Uses a dual-layer architecture: Docker containers for database/backend and Nginx for reverse proxying static assets.

### 1. Compile the Frontend
Build the production optimized build bundles:
```bash
cd frontend
npm run build
```
*Static files are generated inside `frontend/dist`. These should be copied to your server web root (e.g. `/var/www/nifty-portal/dist`).*

### 2. Start the Production Backend Container Stack
In the project root, run the multi-stage Docker build:
```bash
docker compose -f docker-compose.prod.yml up -d --build
```
*This compiles the Jar and spins up `nifty-backend-prod`, `nifty-postgres-prod`, and `nifty-redis-prod` services.*

### 3. Setup Nginx Configuration
Copy the configuration from `nginx.conf` in the project root to your VPS Nginx folder:
```bash
sudo cp nginx.conf /etc/nginx/sites-available/nifty-portal
sudo ln -s /etc/nginx/sites-available/nifty-portal /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```
