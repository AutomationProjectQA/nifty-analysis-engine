import React, { useState } from 'react';
import { Box, Card, CardContent, Typography, Tabs, Tab, TextField, Slider, Divider, Button, Alert } from '@mui/material';
import Grid from '@mui/material/Grid';

import AdSenseSlot from '../components/AdSenseSlot';

const Calculators = () => {
  const [activeTab, setActiveTab] = useState(0);

  // 1. Option Profit Calculator State
  const [optPremium, setOptPremium] = useState(150);
  const [optExit, setOptExit] = useState(180);
  const [optLots, setOptLots] = useState(2); // lot size is 65 for Nifty

  // 2. Position Size State
  const [capTotal, setCapTotal] = useState(100000);
  const [capRiskPct, setCapRiskPct] = useState(2); // 2% risk
  const [stopPoints, setStopPoints] = useState(15);
  const [optionPrice, setOptionPrice] = useState(150);

  // 3. Risk Reward State
  const [entryPrice, setEntryPrice] = useState(150);
  const [slPrice, setSlPrice] = useState(135);
  const [targetPrice, setTargetPrice] = useState(180);

  // 4. SIP State
  const [sipMonthly, setSipMonthly] = useState(5000);
  const [sipReturn, setSipReturn] = useState(12); // 12%
  const [sipYears, setSipYears] = useState(10);

  // 5. Brokerage State
  const [orderCount, setOrderCount] = useState(4); // Buy + Sell count
  const FlatBrokerage = 20.0; // flat 20 rs per executed order

  // Option calculations (guard against a cleared/zero premium -> avoid NaN/Infinity)
  const optProfitValue = (optExit - optPremium) * optLots * 65;
  const optRoi = optPremium > 0 ? ((optExit - optPremium) / optPremium) * 100 : 0;

  // Position sizing
  const maxRiskCash = (capTotal * capRiskPct) / 100;
  const maxLots = Math.floor(maxRiskCash / (stopPoints * 65));
  const reqCapital = maxLots > 0 ? maxLots * 65 * optionPrice : 0;

  // Risk reward calculations
  const riskAmt = entryPrice - slPrice;
  const rewardAmt = targetPrice - entryPrice;
  const rrRatio = riskAmt > 0 ? (rewardAmt / riskAmt).toFixed(2) : '0.00';

  // SIP calculations: S = P * [((1 + i)^n - 1) / i] * (1 + i)
  const monthlyRate = (sipReturn / 12) / 100;
  const totalMonths = sipYears * 12;
  const sipTotalInvested = sipMonthly * totalMonths;
  let sipValue = 0;
  if (monthlyRate > 0) {
    sipValue = sipMonthly * ((Math.pow(1 + monthlyRate, totalMonths) - 1) / monthlyRate) * (1 + monthlyRate);
  } else {
    sipValue = sipTotalInvested;
  }
  const sipReturnEstimated = sipValue - sipTotalInvested;

  // Brokerage calculations
  const totalBrokerage = orderCount * FlatBrokerage;
  const nseTransactionCharges = orderCount * 1.5; // average exchange transaction charge
  const gstCharges = (totalBrokerage + nseTransactionCharges) * 0.18; // 18% GST
  const totalTransactionCost = totalBrokerage + nseTransactionCharges + gstCharges;

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3 }}>
        Trading & Investment Calculators
      </Typography>

      {/* Calculator Selector Tabs */}
      <Tabs 
        value={activeTab} 
        onChange={(e, val) => setActiveTab(val)}
        textColor="primary"
        indicatorColor="primary"
        variant="scrollable"
        scrollButtons="auto"
        sx={{ mb: 4, borderBottom: '1px solid #1e222d' }}
      >
        <Tab label="Option Profit" sx={{ fontWeight: 600 }} />
        <Tab label="Position Size" sx={{ fontWeight: 600 }} />
        <Tab label="Risk Reward" sx={{ fontWeight: 600 }} />
        <Tab label="SIP Calculator" sx={{ fontWeight: 600 }} />
        <Tab label="Brokerage" sx={{ fontWeight: 600 }} />
      </Tabs>

      {/* Main Container Card */}
      <Card>
        <CardContent sx={{ p: 4 }}>
          
          {/* TAB 0: Option Profit Calculator */}
          {activeTab === 0 && (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Position Parameters</Typography>
                
                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Buy Premium Price (INR)</Typography>
                  <TextField type="number" fullWidth value={optPremium} onChange={(e) => setOptPremium(Number(e.target.value))} size="small" />
                </Box>
                
                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Estimated Exit Price (INR)</Typography>
                  <TextField type="number" fullWidth value={optExit} onChange={(e) => setOptExit(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>Number of Lots (65 shares/lot)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: 'primary.main' }}>{optLots} Lots</Typography>
                  </Box>
                  <Slider value={optLots} min={1} max={50} step={1} onChange={(e, val) => setOptLots(val)} color="primary" />
                </Box>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', bgcolor: '#171b26', p: 3, borderRadius: 2, border: '1px solid #1e222d' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600, textAlign: 'center' }}>ESTIMATED NET PNL</Typography>
                <Typography variant="h3" sx={{ fontWeight: 700, textAlign: 'center', mt: 1, mb: 1, fontFamily: 'Outfit, sans-serif', color: optProfitValue >= 0 ? '#26a69a' : '#ef5350' }}>
                  {optProfitValue >= 0 ? '+' : ''}{optProfitValue.toLocaleString('en-IN', { minimumFractionDigits: 2 })}
                </Typography>
                <Typography variant="body2" sx={{ textAlign: 'center', color: optProfitValue >= 0 ? '#26a69a' : '#ef5350', fontWeight: 600, mb: 3 }}>
                  ROI: {optRoi.toFixed(1)}%
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="body2" sx={{ textAlign: 'center', color: 'text.secondary' }}>
                  Total Position Value: <strong>₹{(optPremium * optLots * 65).toLocaleString('en-IN')}</strong>
                </Typography>
              </Grid>
            </Grid>
          )}

          {/* TAB 1: Position Size Calculator */}
          {activeTab === 1 && (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Capital & Risk Limits</Typography>
                
                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Total Wallet Capital (₹)</Typography>
                  <TextField type="number" fullWidth value={capTotal} onChange={(e) => setCapTotal(Number(e.target.value))} size="small" />
                </Box>
                
                <Box sx={{ mb: 3 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>Capital Risk Allowance (%)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{capRiskPct}%</Typography>
                  </Box>
                  <Slider value={capRiskPct} min={1} max={10} step={0.5} onChange={(e, val) => setCapRiskPct(val)} />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Option Stop Loss Points</Typography>
                  <TextField type="number" fullWidth value={stopPoints} onChange={(e) => setStopPoints(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Entry Option Premium Price (₹)</Typography>
                  <TextField type="number" fullWidth value={optionPrice} onChange={(e) => setOptionPrice(Number(e.target.value))} size="small" />
                </Box>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', bgcolor: '#171b26', p: 3, borderRadius: 2, border: '1px solid #1e222d' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600, mb: 1 }}>MAX RISK BUDGET</Typography>
                <Typography variant="h5" sx={{ fontWeight: 700, mb: 3, color: '#ef5350', fontFamily: 'Outfit, sans-serif' }}>
                  ₹{maxRiskCash.toLocaleString('en-IN')}
                </Typography>

                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>RECOMMENDED QUANTITY</Typography>
                <Typography variant="h4" sx={{ fontWeight: 700, mb: 3, color: 'primary.main', fontFamily: 'Outfit, sans-serif' }}>
                  {maxLots} Lots <Typography variant="caption" sx={{ color: 'text.secondary' }}>({maxLots * 65} Shares)</Typography>
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Required Position Cash Allocation: <strong>₹{reqCapital.toLocaleString('en-IN')}</strong>
                </Typography>
              </Grid>
            </Grid>
          )}

          {/* TAB 2: Risk Reward Calculator */}
          {activeTab === 2 && (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Price Targets</Typography>
                
                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Entry Buy Price (₹)</Typography>
                  <TextField type="number" fullWidth value={entryPrice} onChange={(e) => setEntryPrice(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Stop Loss Exit Price (₹)</Typography>
                  <TextField type="number" fullWidth value={slPrice} onChange={(e) => setSlPrice(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Profit Target Exit Price (₹)</Typography>
                  <TextField type="number" fullWidth value={targetPrice} onChange={(e) => setTargetPrice(Number(e.target.value))} size="small" />
                </Box>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', bgcolor: '#171b26', p: 3, borderRadius: 2, border: '1px solid #1e222d' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600, mb: 1 }}>RISK REWARD RATIO</Typography>
                <Typography variant="h4" sx={{ fontWeight: 700, mb: 3, color: '#ffb300', fontFamily: 'Outfit, sans-serif' }}>
                  1 : {rrRatio}
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                  Per-share Risk: <span style={{ color: '#ef5350', fontWeight: 600 }}>₹{riskAmt}</span>
                </Typography>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Per-share Profit: <span style={{ color: '#26a69a', fontWeight: 600 }}>₹{rewardAmt}</span>
                </Typography>
              </Grid>
            </Grid>
          )}

          {/* TAB 3: SIP Calculator */}
          {activeTab === 3 && (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Investment Setup</Typography>
                
                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Monthly Installment Amount (₹)</Typography>
                  <TextField type="number" fullWidth value={sipMonthly} onChange={(e) => setSipMonthly(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>Expected Annual Returns Rate (%)</Typography>
                  <TextField type="number" fullWidth value={sipReturn} onChange={(e) => setSipReturn(Number(e.target.value))} size="small" />
                </Box>

                <Box sx={{ mb: 3 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>Time Horizon (Years)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: 'primary.main' }}>{sipYears} Years</Typography>
                  </Box>
                  <Slider value={sipYears} min={1} max={30} step={1} onChange={(e, val) => setSipYears(val)} />
                </Box>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', bgcolor: '#171b26', p: 3, borderRadius: 2, border: '1px solid #1e222d' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>ESTIMATED PORTFOLIO VALUE</Typography>
                <Typography variant="h3" sx={{ fontWeight: 700, mt: 1, mb: 3, color: 'primary.main', fontFamily: 'Outfit, sans-serif' }}>
                  ₹{Math.round(sipValue).toLocaleString('en-IN')}
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                  Total Principal Invested: <strong>₹{sipTotalInvested.toLocaleString('en-IN')}</strong>
                </Typography>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Interest Wealth Growth: <strong style={{ color: '#26a69a' }}>₹{Math.round(sipReturnEstimated).toLocaleString('en-IN')}</strong>
                </Typography>
              </Grid>
            </Grid>
          )}

          {/* TAB 4: Brokerage Calculator */}
          {activeTab === 4 && (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="h6" sx={{ mb: 3, fontFamily: 'Outfit, sans-serif' }}>Order Details</Typography>
                
                <Box sx={{ mb: 3 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>Total Completed Orders Count (Buy + Sell)</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: 'primary.main' }}>{orderCount} Orders</Typography>
                  </Box>
                  <Slider value={orderCount} min={2} max={100} step={2} onChange={(e, val) => setOrderCount(val)} />
                </Box>
                
                <Alert severity="info" sx={{ bgcolor: '#171b26', border: '1px solid #1e222d', color: '#b2b5be' }}>
                  Calculated based on flat ₹20 brokerage charge per executed options order, which is standard across top discount brokers in India (Zerodha, Angel One, Groww).
                </Alert>
              </Grid>

              <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', bgcolor: '#171b26', p: 3, borderRadius: 2, border: '1px solid #1e222d' }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>TOTAL TRANSACTION CHARGES</Typography>
                <Typography variant="h3" sx={{ fontWeight: 700, mt: 1, mb: 3, color: '#ef5350', fontFamily: 'Outfit, sans-serif' }}>
                  ₹{totalTransactionCost.toFixed(2)}
                </Typography>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                  Base Brokerage Cost: <strong>₹{totalBrokerage.toFixed(2)}</strong>
                </Typography>
                <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                  NSE Transaction Tax: <strong>₹{nseTransactionCharges.toFixed(2)}</strong>
                </Typography>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  GST (18% on Brokerage + Exchange): <strong>₹{gstCharges.toFixed(2)}</strong>
                </Typography>
              </Grid>
            </Grid>
          )}

        </CardContent>
      </Card>

      {/* Ad Placement slot */}
      <AdSenseSlot adSlot="calculators-footer" />
    </Box>
  );
};

export default Calculators;
