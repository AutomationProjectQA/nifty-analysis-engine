import { useState, useEffect } from 'react';
import api from '../api/client';

/**
 * Polls /api/v1/market/feed-status to learn whether the market data currently flowing
 * is LIVE (real broker feed) or SIMULATED (degraded fallback). This is what lets the
 * portal avoid labelling simulated/mock data as "Live".
 *
 * Returns 'LIVE' | 'SIMULATED' | null (null = unknown / not yet checked / unreachable).
 */
export default function useFeedStatus(intervalMs = 10000) {
  const [dataSource, setDataSource] = useState(null);

  useEffect(() => {
    let active = true;

    const check = async () => {
      try {
        const res = await api.get('/api/v1/market/feed-status', { timeout: 5000 });
        if (active) setDataSource(res.data?.dataSource ?? null);
      } catch (err) {
        if (active) setDataSource(null); // unreachable — let the backend-status hook signal offline
      }
    };

    check();
    const id = setInterval(check, intervalMs);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, [intervalMs]);

  return dataSource;
}
