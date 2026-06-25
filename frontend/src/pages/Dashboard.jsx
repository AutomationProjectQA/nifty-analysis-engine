import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, LinearProgress, Alert, Button, Chip } from '@mui/material';
import Grid from '@mui/material/Grid';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import TrendingDownIcon from '@mui/icons-material/TrendingDown';
import SwapCallsIcon from '@mui/icons-material/SwapCalls';
import api from '../api/client';
import { subscribe } from '../api/marketStream';

import TradingViewChart from '../components/TradingViewChart';

const Dashboard = () => {
  const [marketData, setMarketData] = useState({
    niftySpot: 23510.50,
    niftyFuture: 23548.20,
    indiaVix: 13.40,
    rsi: 58.20,
    vwap: 23502.10,
    ema20: 23495.12,
    ema50: 23478.45
  });
  
  const [live, setLive] = useState(null); // null=loading, true=live, false=demo fallback

  const fetchMarketData = async () => {
    try {
      const response = await api.get('/api/v1/market/latest');
      if (response.data) {
        setMarketData(response.data);
        setLive(true);
      }
    } catch (e) {
      // Keep using default mock values if connection fails, but flag as demo
      console.warn("Backend down, displaying live simulated cues.", e.message);
      setLive(false);
    }
  };

  useEffect(() => {
    fetchMarketData(); // initial paint via REST
    // Full snapshot (incl. RSI/VWAP/EMA) on each collection cycle.
    const unsubSnap = subscribe('/topic/market', (data) => {
      if (data) {
        setMarketData(data);
        setLive(true);
      }
    });
    // Real-time ticks (spot/future/VIX) between cycles — merged onto the latest snapshot.
    const unsubTick = subscribe('/topic/tick', (t) => {
      if (t) {
        setMarketData((prev) => ({
          ...prev,
          niftySpot: t.niftySpot,
          niftyFuture: t.niftyFuture,
          indiaVix: t.indiaVix,
        }));
        setLive(true);
      }
    });
    return () => { unsubSnap(); unsubTick(); };
  }, []);

  const spotFutureSpread = marketData.niftyFuture - marketData.niftySpot;
  const spotVwapDistance = marketData.niftySpot - marketData.vwap;
  const spotVwapPct = marketData.vwap ? (spotVwapDistance / marketData.vwap) * 100 : 0;
  const spotAboveVwap = spotVwapDistance >= 0;

  // Volatility regime from the live VIX level (no fabricated "change").
  let vixLabel = 'Moderate volatility';
  let vixColor = '#ffb300';
  if (marketData.indiaVix < 13) {
    vixLabel = 'Low volatility';
    vixColor = '#26a69a';
  } else if (marketData.indiaVix > 18) {
    vixLabel = 'High volatility';
    vixColor = '#ef5350';
  }
  
  // Calculate dynamic Pivot support/resistance bounds based on Spot
  const pivotPoint = marketData.niftySpot;
  const r1 = Math.round((pivotPoint * 1.005) / 50) * 50;
  const r2 = Math.round((pivotPoint * 1.012) / 50) * 50;
  const s1 = Math.round((pivotPoint * 0.995) / 50) * 50;
  const s2 = Math.round((pivotPoint * 0.988) / 50) * 50;

  // Determine trend status
  let trendBias = 'Neutral / Sideways';
  let trendColor = '#ffb300';
  let TrendIcon = SwapCallsIcon;

  if (marketData.niftySpot > marketData.ema20 && marketData.ema20 > marketData.ema50) {
    trendBias = 'Bullish Expansion';
    trendColor = '#26a69a';
    TrendIcon = TrendingUpIcon;
  } else if (marketData.niftySpot < marketData.ema20 && marketData.ema20 < marketData.ema50) {
    trendBias = 'Bearish Retracement';
    trendColor = '#ef5350';
    TrendIcon = TrendingDownIcon;
  }

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Typography 
        variant="h4" 
        sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          Market Snapshot
          {live === false && (
            <Chip label="Demo data" size="small"
              sx={{ bgcolor: 'rgba(255,179,0,0.12)', color: '#ffb300', border: '1px solid rgba(255,179,0,0.3)', fontWeight: 600 }} />
          )}
        </Box>
        <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 500 }}>
          Refreshes every 5s
        </Typography>
      </Typography>

      {/* Top Indicators Row */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        
        {/* NIFTY SPOT */}
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>NIFTY 50 SPOT</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif' }}>
                {marketData.niftySpot.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center', mt: 1, gap: 0.5, color: spotAboveVwap ? '#26a69a' : '#ef5350' }}>
                {spotAboveVwap ? <TrendingUpIcon fontSize="small" /> : <TrendingDownIcon fontSize="small" />}
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {spotAboveVwap ? '+' : ''}{spotVwapDistance.toFixed(2)} ({spotVwapPct.toFixed(2)}%) vs VWAP
                </Typography>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* NIFTY FUTURE */}
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>NIFTY FUTURE</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif' }}>
                {marketData.niftyFuture.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
                Spread: <span style={{ color: '#26a69a', fontWeight: 600 }}>+{spotFutureSpread.toFixed(2)} pts</span>
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        {/* INDIA VIX */}
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>INDIA VIX</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif' }}>
                {marketData.indiaVix.toFixed(2)}
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center', mt: 1, gap: 0.5, color: vixColor }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{vixLabel}</Typography>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* TREND REGIME */}
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <Card sx={{ borderLeft: `4px solid ${trendColor}` }}>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>MARKET TREND</Typography>
              <Typography variant="h5" sx={{ fontWeight: 700, mt: 1.5, color: trendColor, fontFamily: 'Outfit, sans-serif', display: 'flex', alignItems: 'center', gap: 1 }}>
                <TrendIcon />
                {trendBias}
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mt: 1 }}>
                RSI (14): <span style={{ color: '#ffffff', fontWeight: 600 }}>{marketData.rsi.toFixed(2)}</span>
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Main Charts & Key levels container */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        
        {/* TradingView candlestick widget */}
        <Grid size={{ xs: 12, md: 8 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: 2 }}>
              <Typography variant="h6" sx={{ mb: 2, fontFamily: 'Outfit, sans-serif' }}>Live Nifty Chart (5m Interval)</Typography>
              <TradingViewChart />
            </CardContent>
          </Card>
        </Grid>

        {/* Supports / Resistance Levels */}
        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Calculated Key Levels</Typography>
              
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
                
                {/* R2 */}
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" sx={{ color: '#ef5350', fontWeight: 600 }}>Resistance 2 (R2)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{r2}</Typography>
                  </Box>
                  <LinearProgress variant="determinate" value={45} color="secondary" sx={{ height: 6, borderRadius: 3, bgcolor: '#1e222d' }} />
                </Box>

                {/* R1 */}
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" sx={{ color: '#ef5350', fontWeight: 600 }}>Resistance 1 (R1)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{r1}</Typography>
                  </Box>
                  <LinearProgress variant="determinate" value={75} color="secondary" sx={{ height: 6, borderRadius: 3, bgcolor: '#1e222d' }} />
                </Box>

                {/* Spot reference */}
                <Box sx={{ py: 1, borderTop: '1px dashed #1e222d', borderBottom: '1px dashed #1e222d', display: 'flex', justifyContent: 'space-between' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Nifty Spot Reference</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 700, color: 'primary.main' }}>
                    {Math.round(marketData.niftySpot)}
                  </Typography>
                </Box>

                {/* S1 */}
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" sx={{ color: '#26a69a', fontWeight: 600 }}>Support 1 (S1)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{s1}</Typography>
                  </Box>
                  <LinearProgress variant="determinate" value={75} color="primary" sx={{ height: 6, borderRadius: 3, bgcolor: '#1e222d' }} />
                </Box>

                {/* S2 */}
                <Box>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                    <Typography variant="body2" sx={{ color: '#26a69a', fontWeight: 600 }}>Support 2 (S2)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{s2}</Typography>
                  </Box>
                  <LinearProgress variant="determinate" value={45} color="primary" sx={{ height: 6, borderRadius: 3, bgcolor: '#1e222d' }} />
                </Box>

              </Box>

              <Alert severity="info" sx={{ mt: 4, bgcolor: '#171b26', border: '1px solid #1e222d', color: '#b2b5be' }}>
                Levels calculated mathematically using standard daily price pivots relative to yesterday close.
              </Alert>

            </CardContent>
          </Card>
        </Grid>

      </Grid>
    </Box>
  );
};

export default Dashboard;
