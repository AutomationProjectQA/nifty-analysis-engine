import React, { useState, useEffect } from 'react';
import { Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Typography, Card, CardContent, Chip } from '@mui/material';
import Grid from '@mui/material/Grid';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import api from '../api/client';
import { subscribe } from '../api/marketStream';

import AdSenseSlot from '../components/AdSenseSlot';

// Mock Option Chain data fallback if backend is offline
const mockOptionChain = [
  { strikePrice: 23300, ceOi: 420000, peOi: 2450000, ceOiChange: 15000, peOiChange: 450000, iv: 11.2, pcr: 5.83, maxPain: 23500, ceVolume: 120000, peVolume: 650000 },
  { strikePrice: 23350, ceOi: 150000, peOi: 1820000, ceOiChange: 5000, peOiChange: 320000, iv: 11.5, pcr: 12.13, maxPain: 23500, ceVolume: 80000, peVolume: 420000 },
  { strikePrice: 23400, ceOi: 980000, peOi: 3250000, ceOiChange: -45000, peOiChange: 1120000, iv: 11.8, pcr: 3.32, maxPain: 23500, ceVolume: 280000, peVolume: 950000 },
  { strikePrice: 23450, ceOi: 850000, peOi: 1980000, ceOiChange: 120000, peOiChange: 820000, iv: 12.1, pcr: 2.33, maxPain: 23500, ceVolume: 220000, peVolume: 510000 },
  { strikePrice: 23500, ceOi: 3120000, peOi: 3450000, ceOiChange: 850000, peOiChange: 1250000, iv: 12.5, pcr: 1.11, maxPain: 23500, ceVolume: 1250000, peVolume: 1450000 },
  { strikePrice: 23550, ceOi: 2250000, peOi: 1120000, ceOiChange: 980000, peOiChange: -120000, iv: 12.8, pcr: 0.50, maxPain: 23500, ceVolume: 850000, peVolume: 320000 },
  { strikePrice: 23600, ceOi: 4120000, peOi: 950000, ceOiChange: 1450000, peOiChange: -250000, iv: 13.2, pcr: 0.23, maxPain: 23500, ceVolume: 1550000, peVolume: 180000 },
  { strikePrice: 23650, ceOi: 1950000, peOi: 320000, ceOiChange: 650000, peOiChange: -50000, iv: 13.5, pcr: 0.16, maxPain: 23500, ceVolume: 620000, peVolume: 90000 },
  { strikePrice: 23700, ceOi: 2850000, peOi: 150000, ceOiChange: 850000, peOiChange: 10000, iv: 13.8, pcr: 0.05, maxPain: 23500, ceVolume: 920000, peVolume: 40000 }
];

// Drop rows without a valid strike and coerce every numeric field to a real number, so the
// table/chart never hit `.toString()` on undefined, NaN cells, or duplicate/undefined keys.
const sanitizeChain = (rows) =>
  (rows || [])
    .filter((r) => r && r.strikePrice != null)
    .map((r) => ({
      ...r,
      ceOi: Number(r.ceOi) || 0,
      peOi: Number(r.peOi) || 0,
      ceOiChange: Number(r.ceOiChange) || 0,
      peOiChange: Number(r.peOiChange) || 0,
      iv: Number(r.iv) || 0,
      ceVolume: Number(r.ceVolume) || 0,
      peVolume: Number(r.peVolume) || 0,
    }));

const OptionChain = () => {
  const [optionChain, setOptionChain] = useState(mockOptionChain);
  const [spotPrice, setSpotPrice] = useState(23510.50);
  const [vwap, setVwap] = useState(null);
  const [live, setLive] = useState(null); // null=loading, true=live, false=demo fallback

  const fetchOptionData = async () => {
    try {
      const response = await api.get('/api/v1/options/latest');
      const clean = sanitizeChain(response.data);
      if (clean.length > 0) {
        setOptionChain(clean);
      }

      const spotRes = await api.get('/api/v1/market/latest');
      if (spotRes.data && spotRes.data.niftySpot != null) {
        setSpotPrice(spotRes.data.niftySpot);
        setVwap(spotRes.data.vwap ?? null);
      }
      // Only claim "live" when we actually got live chain data; otherwise we're still
      // showing the mock fallback, so keep the demo indicator on.
      setLive(clean.length > 0);
    } catch (e) {
      console.warn("Backend down, showing simulated option chain data.", e.message);
      setLive(false);
    }
  };

  useEffect(() => {
    fetchOptionData(); // initial paint via REST
    // Live updates pushed over WebSocket — no more polling.
    const u1 = subscribe('/topic/options', (data) => {
      const clean = sanitizeChain(data);
      // Only apply + claim live on a real frame; an empty frame must not clear the demo state.
      if (clean.length > 0) {
        setOptionChain(clean);
        setLive(true);
      }
    });
    const u2 = subscribe('/topic/market', (m) => {
      if (m && m.niftySpot != null) {
        setSpotPrice(m.niftySpot);
        setVwap(m.vwap ?? null);
      }
    });
    // Live per-strike OI ticks — merge onto the chain (keep IV/PCR/maxPain from the snapshot).
    const u3 = subscribe('/topic/optionsTick', (ticks) => {
      if (!ticks || !ticks.length) return;
      const byStrike = new Map(ticks.map((t) => [t.strikePrice, t]));
      setOptionChain((prev) => prev.map((row) => {
        const t = byStrike.get(row.strikePrice);
        if (!t) return row;
        return {
          ...row,
          ceOi: Number(t.ceOi) || row.ceOi,
          peOi: Number(t.peOi) || row.peOi,
        };
      }));
      setLive(true);
    });
    return () => { u1(); u2(); u3(); };
  }, []);

  // Compute ATM Strike closest to spot
  // ATM = the actual chain strike nearest to spot (don't assume a fixed 50-pt step, so the
  // ATM highlight still lands on a real row for any strike spacing).
  const atmStrike = optionChain.length
    ? optionChain.reduce((closest, s) =>
        Math.abs(s.strikePrice - spotPrice) < Math.abs(closest - spotPrice) ? s.strikePrice : closest,
        optionChain[0].strikePrice)
    : Math.round(spotPrice / 50) * 50;

  // Compute overall PCR and Max Pain
  let totalCalls = 0;
  let totalPuts = 0;
  optionChain.forEach(s => {
    totalCalls += s.ceOi || 0;
    totalPuts += s.peOi || 0;
  });
  const overallPcr = totalCalls > 0 ? (totalPuts / totalCalls).toFixed(2) : '0.00';
  // Backend stamps the same computed max-pain on every strike; find the first non-null.
  const maxPainVal = optionChain.find((s) => s.maxPain != null)?.maxPain ?? atmStrike;

  // Real intraday direction of the underlying: spot vs day VWAP (fallback: assume up).
  const underlyingUp = vwap != null ? spotPrice > vwap : true;

  // Format Recharts data
  const chartData = optionChain.map(s => ({
    name: s.strikePrice.toString(),
    CallOI: Math.round((s.ceOi || 0) / 100000) / 10, // in Lakhs
    PutOI: Math.round((s.peOi || 0) / 100000) / 10 // in Lakhs
  }));

  // Determine BuildUp Color styles
  const getBuildUpStyle = (isCall, oiChange, priceChange) => {
    // Standard approximation:
    // Long build-up (Green): OI rises, price rises
    // Short Covering (Light Green): OI falls, price rises
    // Short build-up (Red): OI rises, price falls
    // Long unwinding (Orange): OI falls, price falls
    const isUp = priceChange > 0;
    if (oiChange >= 0) {
      return isUp 
        ? { label: 'LBU', bg: 'rgba(38, 166, 154, 0.1)', color: '#26a69a' } // Long Build Up
        : { label: 'SBU', bg: 'rgba(239, 83, 80, 0.1)', color: '#ef5350' };  // Short Build Up
    } else {
      return isUp 
        ? { label: 'SC', bg: 'rgba(0, 188, 212, 0.1)', color: '#00bcd4' }   // Short Covering
        : { label: 'LU', bg: 'rgba(255, 179, 0, 0.1)', color: '#ffb300' };   // Long Unwinding
    }
  };

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700 }}>
          Option Chain Analytics
        </Typography>
        {live === null && (
          <Chip label="Connecting…" size="small"
            sx={{ bgcolor: 'rgba(107,113,133,0.12)', color: 'text.secondary', border: '1px solid rgba(107,113,133,0.25)', fontWeight: 600 }} />
        )}
        {live === false && (
          <Chip label="Demo data — not live" size="small"
            sx={{ bgcolor: 'rgba(255,159,10,0.12)', color: '#ff9f0a', border: '1px solid rgba(255,159,10,0.3)', fontWeight: 600 }} />
        )}
      </Box>

      {/* Summary Cards */}
      <Grid container spacing={3} sx={{ mb: 4 }}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>NIFTY SPOT</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif', color: 'primary.main' }}>
                {spotPrice.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>OVERALL PCR (OI)</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif', color: parseFloat(overallPcr) >= 1.0 ? 'primary.main' : 'secondary.main' }}>
                {overallPcr}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>MAX PAIN STRIKE</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif', color: 'warning.main' }}>
                {maxPainVal.toLocaleString('en-IN')}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Open Interest Bar Chart */}
      <Card sx={{ mb: 4 }}>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 2, fontFamily: 'Outfit, sans-serif' }}>Strike-wise Call vs Put Open Interest (Lakh Contracts)</Typography>
          <Box sx={{ width: '100%', height: 300 }}>
            <ResponsiveContainer>
              <BarChart data={chartData} margin={{ top: 20, right: 30, left: 20, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e9eaf2" />
                <XAxis dataKey="name" stroke="#6b7185" interval="preserveStartEnd" tick={{ fontSize: 11 }} />
                <YAxis stroke="#6b7185" />
                <Tooltip contentStyle={{ backgroundColor: '#ffffff', borderColor: '#e9eaf2', borderRadius: 10, color: '#1b1d28', boxShadow: '0 4px 12px rgba(16,24,40,0.08)' }} />
                <Legend />
                <Bar dataKey="CallOI" fill="#e0483d" name="Calls (CE OI)" radius={[4, 4, 0, 0]} />
                <Bar dataKey="PutOI" fill="#00b386" name="Puts (PE OI)" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </Box>
        </CardContent>
      </Card>

      {/* Option Chain Table — flows with the page (no inner scroll) */}
      <TableContainer component={Paper} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2, overflowX: 'auto' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              {/* Calls Side */}
              <TableCell align="center" colSpan={4} sx={{ bgcolor: 'rgba(239, 83, 80, 0.05)', color: '#ef5350', borderRight: '2px solid #e9eaf2', fontWeight: 700 }}>CALLS (CE)</TableCell>
              {/* Strike Column */}
              <TableCell align="center" sx={{ bgcolor: '#f7f8fc', fontWeight: 700 }}>STRIKE</TableCell>
              {/* Puts Side */}
              <TableCell align="center" colSpan={4} sx={{ bgcolor: 'rgba(38, 166, 154, 0.05)', color: '#26a69a', borderLeft: '2px solid #e9eaf2', fontWeight: 700 }}>PUTS (PE)</TableCell>
            </TableRow>
            <TableRow>
              {/* Call Headers */}
              <TableCell align="right" sx={{ bgcolor: '#f7f8fc' }}>Volume</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#f7f8fc' }}>IV</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#f7f8fc' }}>OI Chg</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#f7f8fc', borderRight: '2px solid #e9eaf2' }}>OI (Lakh)</TableCell>
              {/* Strike Header */}
              <TableCell align="center" sx={{ bgcolor: '#eef0f6', fontWeight: 600 }}>Strike Price</TableCell>
              {/* Put Headers */}
              <TableCell align="left" sx={{ bgcolor: '#f7f8fc', borderLeft: '2px solid #e9eaf2' }}>OI (Lakh)</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#f7f8fc' }}>OI Chg</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#f7f8fc' }}>IV</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#f7f8fc' }}>Volume</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {optionChain.map((row) => {
              const isAtm = row.strikePrice === atmStrike;
              
              // Calls gain when the underlying rises; puts gain when it falls.
              const ceStyle = getBuildUpStyle(true, row.ceOiChange || 0, underlyingUp ? 1 : -1);
              const peStyle = getBuildUpStyle(false, row.peOiChange || 0, underlyingUp ? -1 : 1);

              return (
                <TableRow 
                  key={row.strikePrice} 
                  sx={{ 
                    bgcolor: isAtm ? 'rgba(38, 166, 154, 0.05)' : 'transparent',
                    '&:hover': { bgcolor: 'action.hover' }
                  }}
                >
                  {/* CE Volume */}
                  <TableCell align="right">{(row.ceVolume || 0).toLocaleString('en-IN')}</TableCell>
                  {/* CE IV */}
                  <TableCell align="right" sx={{ color: 'text.secondary' }}>{(row.iv || 0.0).toFixed(1)}%</TableCell>
                  {/* CE OI Chg */}
                  <TableCell align="right">
                    <Chip 
                      label={`${row.ceOiChange >= 0 ? '+' : ''}${Math.round(row.ceOiChange / 1000).toLocaleString('en-IN')}k`} 
                      size="small"
                      sx={{ bgcolor: ceStyle.bg, color: ceStyle.color, fontWeight: 700, fontSize: '0.75rem', borderRadius: 1 }}
                    />
                  </TableCell>
                  {/* CE OI */}
                  <TableCell align="right" sx={{ borderRight: '2px solid #e9eaf2', fontWeight: 600 }}>
                    {Math.round(row.ceOi / 100000).toLocaleString('en-IN')} L
                  </TableCell>

                  {/* STRIKE PRICE */}
                  <TableCell align="center" sx={{ bgcolor: isAtm ? 'rgba(38, 166, 154, 0.18)' : '#f7f8fc', fontWeight: 700, borderLeft: '1px solid', borderRight: '1px solid', borderColor: 'divider' }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 0.75 }}>
                      {row.strikePrice}
                      {isAtm && (
                        <Chip label="ATM" size="small" color="primary"
                          sx={{ height: 18, fontSize: '0.6rem', fontWeight: 700, '& .MuiChip-label': { px: 0.75 } }} />
                      )}
                    </Box>
                  </TableCell>

                  {/* PE OI */}
                  <TableCell align="left" sx={{ borderLeft: '2px solid #e9eaf2', fontWeight: 600 }}>
                    {Math.round(row.peOi / 100000).toLocaleString('en-IN')} L
                  </TableCell>
                  {/* PE OI Chg */}
                  <TableCell align="left">
                    <Chip 
                      label={`${row.peOiChange >= 0 ? '+' : ''}${Math.round(row.peOiChange / 1000).toLocaleString('en-IN')}k`} 
                      size="small"
                      sx={{ bgcolor: peStyle.bg, color: peStyle.color, fontWeight: 700, fontSize: '0.75rem', borderRadius: 1 }}
                    />
                  </TableCell>
                  {/* PE IV */}
                  <TableCell align="left" sx={{ color: 'text.secondary' }}>{(row.iv || 0.0).toFixed(1)}%</TableCell>
                  {/* PE Volume */}
                  <TableCell align="left">{(row.peVolume || 0).toLocaleString('en-IN')}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Footer Ad Placement */}
      <AdSenseSlot adSlot="options-chain-footer" />
    </Box>
  );
};

export default OptionChain;
