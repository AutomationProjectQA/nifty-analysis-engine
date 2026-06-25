import React, { useEffect, useRef } from 'react';
import { Box } from '@mui/material';

const TradingViewChart = () => {
  const container = useRef();

  useEffect(() => {
    if (!container.current) return;
    // Clear any existing widgets
    container.current.innerHTML = '';

    const script = document.createElement("script");
    script.src = "https://s3.tradingview.com/external-embedding/embed-widget-advanced-chart.js";
    script.type = "text/javascript";
    script.async = true;
    script.innerHTML = JSON.stringify({
      "autosize": true,
      "symbol": "TVC:NIFTY",
      "interval": "5",
      "timezone": "Asia/Kolkata",
      "theme": "dark",
      "style": "1",
      "locale": "en",
      "enable_publishing": false,
      "hide_side_toolbar": false,
      "allow_symbol_change": false,
      "calendar": false,
      "support_host": "https://www.tradingview.com"
    });
    
    container.current.appendChild(script);
  }, []);

  return (
    <Box 
      className="tradingview-widget-container" 
      ref={container} 
      sx={{ height: 420, width: "100%", bgcolor: "#131722", borderRadius: 2, overflow: "hidden", border: "1px solid #1e222d" }}
    />
  );
};

export default TradingViewChart;
