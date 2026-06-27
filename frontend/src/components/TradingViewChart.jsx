import React, { useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, Typography, Button, IconButton, Tooltip } from '@mui/material';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import FullscreenIcon from '@mui/icons-material/Fullscreen';
import FullscreenExitIcon from '@mui/icons-material/FullscreenExit';
import PropTypes from 'prop-types';

// Use the tv.js widget constructor (NOT the embed-widget script). The embed script reads its
// config from document.currentScript, which is null when injected dynamically — so it ignores
// our symbol and falls back to its default (NASDAQ:AAPL). The constructor takes the symbol
// directly, so the Nifty chart renders reliably.
const TV_LIB = 'https://s3.tradingview.com/tv.js';

const TradingViewChart = ({ symbol = 'NSE:NIFTY', interval = '5', height = 420 }) => {
  const wrapperRef = useRef(null);
  const containerRef = useRef(null);
  const idRef = useRef('tv_' + Math.random().toString(36).slice(2));
  const [status, setStatus] = useState('loading'); // 'loading' | 'ready' | 'error'
  const [isFullscreen, setIsFullscreen] = useState(false);

  useEffect(() => {
    let settled = false;
    let observer = null;
    let timeout = null;
    const markReady = () => { if (!settled) { settled = true; setStatus('ready'); } };
    const markError = () => { if (!settled) { settled = true; setStatus('error'); } };
    setStatus('loading');

    const build = () => {
      const host = containerRef.current;
      if (!host || !window.TradingView || !window.TradingView.widget) { markError(); return; }
      host.innerHTML = '';
      const inner = document.createElement('div');
      inner.id = idRef.current;
      inner.style.height = '100%';
      inner.style.width = '100%';
      host.appendChild(inner);

      try {
        // eslint-disable-next-line no-new
        new window.TradingView.widget({
          container_id: idRef.current,
          autosize: true,
          symbol,
          interval,
          timezone: 'Asia/Kolkata',
          theme: 'light',
          style: '1',
          locale: 'en',
          enable_publishing: false,
          allow_symbol_change: false,
          hide_side_toolbar: false,
        });
      } catch (e) {
        markError();
        return;
      }

      // The widget mounts an <iframe> asynchronously — flip to "ready" when it appears.
      observer = new MutationObserver(() => { if (host.querySelector('iframe')) markReady(); });
      observer.observe(host, { childList: true, subtree: true });
      timeout = setTimeout(() => {
        if (host.querySelector('iframe')) markReady(); else markError();
      }, 12000);
    };

    if (window.TradingView && window.TradingView.widget) {
      build();
    } else {
      let lib = document.getElementById('tv-js-lib');
      if (!lib) {
        lib = document.createElement('script');
        lib.id = 'tv-js-lib';
        lib.src = TV_LIB;
        lib.async = true;
        lib.onload = build;
        lib.onerror = markError;
        document.head.appendChild(lib);
      } else {
        lib.addEventListener('load', build, { once: true });
        lib.addEventListener('error', markError, { once: true });
      }
    }

    return () => {
      if (observer) observer.disconnect();
      if (timeout) clearTimeout(timeout);
      const host = containerRef.current;
      if (host) host.innerHTML = '';
    };
  }, [symbol, interval]);

  // Track native fullscreen state so the icon + height reflect it.
  useEffect(() => {
    const onFs = () => setIsFullscreen(document.fullscreenElement === wrapperRef.current);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  const toggleFullscreen = () => {
    const el = wrapperRef.current;
    if (!el) return;
    if (!document.fullscreenElement) {
      el.requestFullscreen?.();
    } else {
      document.exitFullscreen?.();
    }
  };

  return (
    <Box
      ref={wrapperRef}
      sx={{
        position: 'relative',
        height: isFullscreen ? '100vh' : height,
        width: '100%',
        bgcolor: 'background.paper',
        borderRadius: isFullscreen ? 0 : 2,
        overflow: 'hidden',
        border: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Box ref={containerRef} sx={{ height: '100%', width: '100%' }} />

      {/* Fullscreen toggle */}
      {status === 'ready' && (
        <Tooltip title={isFullscreen ? 'Exit fullscreen' : 'Fullscreen'}>
          <IconButton
            onClick={toggleFullscreen}
            size="small"
            aria-label="Toggle fullscreen chart"
            sx={{
              position: 'absolute', top: 8, right: 8, zIndex: 2,
              bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider',
              '&:hover': { bgcolor: 'action.hover' },
            }}
          >
            {isFullscreen ? <FullscreenExitIcon fontSize="small" /> : <FullscreenIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
      )}

      {status === 'loading' && (
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1.5, bgcolor: 'background.paper' }}>
          <CircularProgress size={28} color="primary" />
          <Typography variant="body2" color="text.secondary">Loading live chart…</Typography>
        </Box>
      )}

      {status === 'error' && (
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1.5, textAlign: 'center', px: 3, bgcolor: 'background.paper' }}>
          <ShowChartIcon sx={{ fontSize: 40, color: 'text.disabled' }} />
          <Typography variant="body2" color="text.secondary">Live chart couldn&apos;t load right now.</Typography>
          <Button
            size="small" variant="outlined" color="primary" endIcon={<OpenInNewIcon />}
            href={`https://www.tradingview.com/symbols/${symbol.replace(':', '-')}/`}
            target="_blank" rel="noopener noreferrer"
          >
            Open on TradingView
          </Button>
        </Box>
      )}
    </Box>
  );
};

TradingViewChart.propTypes = {
  symbol: PropTypes.string,
  interval: PropTypes.string,
  height: PropTypes.number,
};

export default TradingViewChart;
