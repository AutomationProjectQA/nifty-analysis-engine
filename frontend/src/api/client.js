import axios from 'axios';

// Base URL resolution:
//  - dev:  VITE_API_BASE_URL=http://localhost:8080  (.env.development)
//  - prod: VITE_API_BASE_URL=                       (.env.production — same origin, nginx proxies /api)
//  - if the var is entirely undefined, fall back to localhost for safety.
const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const api = axios.create({
  baseURL,
  timeout: 8000,
});

export default api;
