import { useState, useEffect } from 'react';
import api from '../api/client';

/**
 * Periodically checks whether the backend is healthy via /api/v1/health.
 * Returns: null while the first check is in flight, true if healthy (200), false otherwise
 * (network failure or 503 = critical dependency down).
 */
export default function useBackendStatus(intervalMs = 15000) {
  const [online, setOnline] = useState(null);

  useEffect(() => {
    let active = true;

    const check = async () => {
      try {
        const res = await api.get('/api/v1/health', { timeout: 5000 });
        if (active) setOnline(res.status === 200);
      } catch (err) {
        if (active) setOnline(false); // network error or 503 => not healthy
      }
    };

    check();
    const id = setInterval(check, intervalMs);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, [intervalMs]);

  return online;
}
