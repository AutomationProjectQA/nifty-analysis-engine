import React, { useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, Typography } from '@mui/material';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import { createChart } from 'lightweight-charts';
import PropTypes from 'prop-types';
import api from '../api/client';
import { subscribe } from '../api/marketStream';

/**
 * Self-rendered Nifty candlestick chart fed by our OWN collected candle data
 * (/api/v1/market/candles) and updated live from the WebSocket tick stream.
 *
 * We do NOT use the TradingView embed widget: NSE/Nifty symbols are restricted in the free
 * widget ("This symbol is only available on TradingView"), so it could never show a Nifty chart
 * and fell back to a default (Apple). This component has no external symbol/licensing dependency.
 */
const NiftyCandleChart = ({ instrument = 'NIFTY', timeframe = '5m', height = 420 }) => {
  const containerRef = useRef(null);
  const chartRef = useRef(null);
  const seriesRef = useRef(null);
  const lastBarRef = useRef(null); // the forming (latest) candle, updated live by ticks
  const [status, setStatus] = useState('loading'); // 'loading' | 'ready' | 'empty'

  // Build the chart once.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const chart = createChart(el, {
      width: el.clientWidth,
      height,
      layout: { background: { color: 'transparent' }, textColor: '#6b7185' },
      grid: { vertLines: { color: '#eef0f6' }, horzLines: { color: '#eef0f6' } },
      rightPriceScale: { borderColor: '#e9eaf2' },
      timeScale: { borderColor: '#e9eaf2', timeVisible: true, secondsVisible: false },
      crosshair: { mode: 0 },
      handleScale: true,
      handleScroll: true,
    });
    const series = chart.addCandlestickSeries({
      upColor: '#26a69a', downColor: '#ef5350', borderVisible: false,
      wickUpColor: '#26a69a', wickDownColor: '#ef5350',
    });
    chartRef.current = chart;
    seriesRef.current = series;

    const ro = new ResizeObserver(() => {
      if (containerRef.current) chart.applyOptions({ width: containerRef.current.clientWidth });
    });
    ro.observe(el);

    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
    };
  }, [height]);

  // Load candles + wire live updates.
  useEffect(() => {
    let active = true;

    const load = async () => {
      try {
        const res = await api.get('/api/v1/market/candles', { params: { instrument, timeframe, limit: 200 } });
        if (!active || !seriesRef.current) return;
        const data = (res.data || [])
          .filter((c) => c && c.time != null)
          .map((c) => ({
            time: Number(c.time),
            open: Number(c.open), high: Number(c.high), low: Number(c.low), close: Number(c.close),
          }));
        if (data.length === 0) {
          setStatus('empty');
          return;
        }
        seriesRef.current.setData(data);
        lastBarRef.current = data[data.length - 1];
        chartRef.current?.timeScale().fitContent();
        setStatus('ready');
      } catch (e) {
        if (active) setStatus((s) => (s === 'ready' ? s : 'empty'));
      }
    };

    load();

    // New completed candles arrive each collection cycle — refetch on the full snapshot push.
    const unsubSnap = subscribe('/topic/market', () => load());

    // Real-time ticks update the forming (last) candle's close/high/low.
    const unsubTick = subscribe('/topic/tick', (t) => {
      const spot = t && Number(t.niftySpot);
      const bar = lastBarRef.current;
      if (!spot || !bar || !seriesRef.current || instrument !== 'NIFTY') return;
      const updated = {
        time: bar.time,
        open: bar.open,
        high: Math.max(bar.high, spot),
        low: Math.min(bar.low, spot),
        close: spot,
      };
      lastBarRef.current = updated;
      try { seriesRef.current.update(updated); } catch (_) { /* out-of-order guard */ }
    });

    return () => { active = false; unsubSnap(); unsubTick(); };
  }, [instrument, timeframe]);

  return (
    <Box sx={{ position: 'relative', height, width: '100%' }}>
      <Box ref={containerRef} sx={{ height: '100%', width: '100%' }} />

      {status === 'loading' && (
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1.5 }}>
          <CircularProgress size={28} color="primary" />
          <Typography variant="body2" color="text.secondary">Loading Nifty chart…</Typography>
        </Box>
      )}

      {status === 'empty' && (
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1, textAlign: 'center', px: 3 }}>
          <ShowChartIcon sx={{ fontSize: 40, color: 'text.disabled' }} />
          <Typography variant="body2" color="text.secondary">
            No candle data yet. The chart fills in as the market feed collects candles.
          </Typography>
        </Box>
      )}
    </Box>
  );
};

NiftyCandleChart.propTypes = {
  instrument: PropTypes.string,
  timeframe: PropTypes.string,
  height: PropTypes.number,
};

export default NiftyCandleChart;
