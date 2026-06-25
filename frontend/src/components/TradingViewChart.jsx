import React, { useEffect, useRef, useState } from 'react';
import { Box, CircularProgress, Typography, Button } from '@mui/material';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import PropTypes from 'prop-types';

const TV_SCRIPT_SRC =
  'https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js';

/**
 * Embeds the TradingView Advanced Chart. Robust against a slow/blocked CDN:
 * shows a spinner while the widget loads and a graceful fallback (with a link
 * out to tradingview.com) if the external script fails or never renders.
 */
const TradingViewChart = ({ symbol = 'NSE:NIFTY', interval = '5', height = 420 }) => {
  const container = useRef(null);
  const [status, setStatus] = useState('loading'); // 'loading' | 'ready' | 'error'

  useEffect(() => {
    const host = container.current;
    if (!host) return;

    let settled = false;
    const markReady = () => {
      if (!settled) {
        settled = true;
        setStatus('ready');
      }
    };
    const markError = () => {
      if (!settled) {
        settled = true;
        setStatus('error');
      }
    };

    setStatus('loading');
    host.innerHTML = '';

    // TradingView renders the widget into this inner element / an iframe sibling.
    const widgetEl = document.createElement('div');
    widgetEl.className = 'tradingview-widget-container__widget';
    widgetEl.style.height = '100%';
    widgetEl.style.width = '100%';
    host.appendChild(widgetEl);

    const script = document.createElement('script');
    script.src = TV_SCRIPT_SRC;
    script.type = 'text/javascript';
    script.async = true;
    script.innerHTML = JSON.stringify({
      autosize: true,
      symbol,
      interval,
      timezone: 'Asia/Kolkata',
      theme: 'light',
      style: '1',
      locale: 'en',
      enable_publishing: false,
      hide_side_toolbar: false,
      allow_symbol_change: false,
      calendar: false,
      support_host: 'https://www.tradingview.com',
    });
    script.onerror = markError;
    host.appendChild(script);

    // The widget mounts an <iframe> async — watch for it to flip to "ready".
    const observer = new MutationObserver(() => {
      if (host.querySelector('iframe')) markReady();
    });
    observer.observe(host, { childList: true, subtree: true });

    // If nothing rendered within 12s, treat the CDN as unavailable.
    const timeout = setTimeout(() => {
      if (host.querySelector('iframe')) markReady();
      else markError();
    }, 12000);

    return () => {
      observer.disconnect();
      clearTimeout(timeout);
      host.innerHTML = '';
    };
  }, [symbol, interval]);

  return (
    <Box
      sx={{
        position: 'relative',
        height,
        width: '100%',
        bgcolor: 'background.paper',
        borderRadius: 2,
        overflow: 'hidden',
        border: '1px solid',
        borderColor: 'divider',
      }}
    >
      <Box ref={container} className="tradingview-widget-container" sx={{ height: '100%', width: '100%' }} />

      {status === 'loading' && (
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 1.5,
            bgcolor: 'background.paper',
          }}
        >
          <CircularProgress size={28} color="primary" />
          <Typography variant="body2" color="text.secondary">
            Loading live chart…
          </Typography>
        </Box>
      )}

      {status === 'error' && (
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 1.5,
            textAlign: 'center',
            px: 3,
            bgcolor: 'background.paper',
          }}
        >
          <ShowChartIcon sx={{ fontSize: 40, color: 'text.disabled' }} />
          <Typography variant="body2" color="text.secondary">
            Live chart couldn&apos;t load right now.
          </Typography>
          <Button
            size="small"
            variant="outlined"
            color="primary"
            endIcon={<OpenInNewIcon />}
            href={`https://www.tradingview.com/symbols/${symbol.replace(':', '-')}/`}
            target="_blank"
            rel="noopener noreferrer"
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
