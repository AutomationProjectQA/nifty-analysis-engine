import React, { useState, useEffect } from 'react';
import { Box, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Paper, Typography, Card, CardContent, Chip } from '@mui/material';
import Grid from '@mui/material/Grid';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import axios from 'axios';

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

const OptionChain = () => {
  const [optionChain, setOptionChain] = useState(mockOptionChain);
  const [spotPrice, setSpotPrice] = useState(23510.50);

  const fetchOptionData = async () => {
    try {
      const response = await axios.get('http://localhost:8080/api/v1/options/latest');
      if (response.data && response.data.length > 0) {
        setOptionChain(response.data);
      }
      
      const spotRes = await axios.get('http://localhost:8080/api/v1/market/latest');
      if (spotRes.data) {
        setSpotPrice(spotRes.data.niftySpot);
      }
    } catch (e) {
      console.warn("Backend down, showing simulated option chain data.", e.message);
    }
  };

  useEffect(() => {
    fetchOptionData();
    const interval = setInterval(fetchOptionData, 10000); // refresh every 10s
    return () => clearInterval(interval);
  }, []);

  // Compute ATM Strike closest to spot
  const atmStrike = Math.round(spotPrice / 50) * 50;

  // Compute overall PCR and Max Pain
  let totalCalls = 0;
  let totalPuts = 0;
  optionChain.forEach(s => {
    totalCalls += s.ceOi || 0;
    totalPuts += s.peOi || 0;
  });
  const overallPcr = totalCalls > 0 ? (totalPuts / totalCalls).toFixed(2) : '0.00';
  const maxPainVal = optionChain[0]?.maxPain || 23500;

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
      <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3 }}>
        Option Chain Analytics
      </Typography>

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
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif', color: parseFloat(overallPcr) >= 1.0 ? '#26a69a' : '#ef5350' }}>
                {overallPcr}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>MAX PAIN STRIKE</Typography>
              <Typography variant="h4" sx={{ fontWeight: 700, mt: 1, fontFamily: 'Outfit, sans-serif', color: '#ffb300' }}>
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
                <CartesianGrid strokeDasharray="3 3" stroke="#1e222d" />
                <XAxis dataKey="name" stroke="#b2b5be" />
                <YAxis stroke="#b2b5be" />
                <Tooltip contentStyle={{ backgroundColor: '#131722', borderColor: '#1e222d', color: '#ffffff' }} />
                <Legend />
                <Bar dataKey="CallOI" fill="#ef5350" name="Calls (CE OI)" />
                <Bar dataKey="PutOI" fill="#26a69a" name="Puts (PE OI)" />
              </BarChart>
            </ResponsiveContainer>
          </Box>
        </CardContent>
      </Card>

      {/* Option Chain Table */}
      <TableContainer component={Paper} sx={{ border: '1px solid #1e222d', maxHeight: 650, borderRadius: 2 }}>
        <Table stickyHeader size="small">
          <TableHead>
            <TableRow>
              {/* Calls Side */}
              <TableCell align="center" colSpan={4} sx={{ bgcolor: 'rgba(239, 83, 80, 0.05)', color: '#ef5350', borderRight: '2px solid #1e222d', fontWeight: 700 }}>CALLS (CE)</TableCell>
              {/* Strike Column */}
              <TableCell align="center" sx={{ bgcolor: '#171b26', fontWeight: 700 }}>STRIKE</TableCell>
              {/* Puts Side */}
              <TableCell align="center" colSpan={4} sx={{ bgcolor: 'rgba(38, 166, 154, 0.05)', color: '#26a69a', borderLeft: '2px solid #1e222d', fontWeight: 700 }}>PUTS (PE)</TableCell>
            </TableRow>
            <TableRow>
              {/* Call Headers */}
              <TableCell align="right" sx={{ bgcolor: '#171b26' }}>Volume</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#171b26' }}>IV</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#171b26' }}>OI Chg</TableCell>
              <TableCell align="right" sx={{ bgcolor: '#171b26', borderRight: '2px solid #1e222d' }}>OI (Lakh)</TableCell>
              {/* Strike Header */}
              <TableCell align="center" sx={{ bgcolor: '#1b2030', fontWeight: 600 }}>Strike Price</TableCell>
              {/* Put Headers */}
              <TableCell align="left" sx={{ bgcolor: '#171b26', borderLeft: '2px solid #1e222d' }}>OI (Lakh)</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#171b26' }}>OI Chg</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#171b26' }}>IV</TableCell>
              <TableCell align="left" sx={{ bgcolor: '#171b26' }}>Volume</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {optionChain.map((row) => {
              const isAtm = row.strikePrice === atmStrike;
              
              // Call direction approximation (Spot change as proxy for option price)
              const ceStyle = getBuildUpStyle(true, row.ceOiChange || 0, spotPrice - 23450.0);
              // Put direction approximation
              const peStyle = getBuildUpStyle(false, row.peOiChange || 0, spotPrice - 23450.0);

              return (
                <TableRow 
                  key={row.strikePrice} 
                  sx={{ 
                    bgcolor: isAtm ? 'rgba(38, 166, 154, 0.05)' : 'transparent',
                    '&:hover': { bgcolor: 'rgba(255, 255, 255, 0.02) !important' }
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
                  <TableCell align="right" sx={{ borderRight: '2px solid #1e222d', fontWeight: 600 }}>
                    {Math.round(row.ceOi / 100000).toLocaleString('en-IN')} L
                  </TableCell>

                  {/* STRIKE PRICE */}
                  <TableCell align="center" sx={{ bgcolor: isAtm ? 'rgba(38, 166, 154, 0.15)' : '#171b26', fontWeight: 700, borderLeft: '1px solid #1e222d', borderRight: '1px solid #1e222d' }}>
                    {row.strikePrice}
                  </TableCell>

                  {/* PE OI */}
                  <TableCell align="left" sx={{ borderLeft: '2px solid #1e222d', fontWeight: 600 }}>
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
