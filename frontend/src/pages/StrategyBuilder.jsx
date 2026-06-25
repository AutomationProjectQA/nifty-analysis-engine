import React, { useState, useEffect, useMemo } from 'react';
import {
  Box, Card, CardContent, Typography, Button, IconButton, Chip, MenuItem, TextField,
  ToggleButton, ToggleButtonGroup, Divider, Stack, Tooltip as MuiTooltip, CircularProgress,
} from '@mui/material';
import Grid from '@mui/material/Grid';
import DeleteOutlineIcon from '@mui/icons-material/Delete';
import AddIcon from '@mui/icons-material/Add';
import AutoGraphIcon from '@mui/icons-material/Insights';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine,
} from 'recharts';
import api from '../api/client';

const LOT_SIZE = 65; // matches backend nifty.order-execution.lot-size

// --- payoff math -----------------------------------------------------------
const intrinsic = (type, strike, spot) =>
  type === 'CE' ? Math.max(spot - strike, 0) : Math.max(strike - spot, 0);

// Per-leg P&L (in points) at a given expiry spot. BUY = +, SELL = -.
const legPnl = (leg, spot) => {
  const sign = leg.action === 'BUY' ? 1 : -1;
  return sign * (intrinsic(leg.type, leg.strike, spot) - leg.premium) * leg.lots * LOT_SIZE;
};

const fmtInr = (v) =>
  `${v < 0 ? '-' : ''}₹${Math.abs(Math.round(v)).toLocaleString('en-IN')}`;

// --- pre-built strategies --------------------------------------------------
// Each returns an array of legs given the ATM strike, step, and a premium lookup.
const STRATEGIES = [
  {
    key: 'long-straddle', name: 'Long Straddle', view: 'Big move (either way)',
    build: (atm, step, p) => [
      { action: 'BUY', type: 'CE', strike: atm }, { action: 'BUY', type: 'PE', strike: atm },
    ],
  },
  {
    key: 'short-straddle', name: 'Short Straddle', view: 'Range-bound / low move',
    build: (atm, step, p) => [
      { action: 'SELL', type: 'CE', strike: atm }, { action: 'SELL', type: 'PE', strike: atm },
    ],
  },
  {
    key: 'long-strangle', name: 'Long Strangle', view: 'Big move, cheaper',
    build: (atm, step, p) => [
      { action: 'BUY', type: 'CE', strike: atm + 2 * step }, { action: 'BUY', type: 'PE', strike: atm - 2 * step },
    ],
  },
  {
    key: 'bull-call', name: 'Bull Call Spread', view: 'Moderately bullish',
    build: (atm, step, p) => [
      { action: 'BUY', type: 'CE', strike: atm }, { action: 'SELL', type: 'CE', strike: atm + 2 * step },
    ],
  },
  {
    key: 'bear-put', name: 'Bear Put Spread', view: 'Moderately bearish',
    build: (atm, step, p) => [
      { action: 'BUY', type: 'PE', strike: atm }, { action: 'SELL', type: 'PE', strike: atm - 2 * step },
    ],
  },
  {
    key: 'iron-condor', name: 'Iron Condor', view: 'Range-bound, defined risk',
    build: (atm, step, p) => [
      { action: 'SELL', type: 'CE', strike: atm + 2 * step }, { action: 'BUY', type: 'CE', strike: atm + 4 * step },
      { action: 'SELL', type: 'PE', strike: atm - 2 * step }, { action: 'BUY', type: 'PE', strike: atm - 4 * step },
    ],
  },
];

let LEG_ID = 1;

const StrategyBuilder = () => {
  const [data, setData] = useState(null);   // { spot, expiry, daysToExpiry, premiums }
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [legs, setLegs] = useState([]);

  // add-leg form
  const [form, setForm] = useState({ action: 'BUY', type: 'CE', strike: '' });

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const res = await api.get('/api/v1/options/premiums');
        if (!active) return;
        setData(res.data);
        setForm((f) => ({ ...f, strike: nearestAtm(res.data) }));
      } catch (e) {
        if (active) setError('Could not load option premiums. Is the backend running?');
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, []);

  const strikes = useMemo(() => (data?.premiums || []).map((p) => p.strike), [data]);
  const spot = data?.spot || 0;
  const step = strikes.length >= 2 ? strikes[1] - strikes[0] : 50;
  const atm = useMemo(() => nearestAtm(data), [data]);

  const premiumFor = (type, strike) => {
    const row = (data?.premiums || []).find((p) => p.strike === strike);
    if (!row) return 0;
    return type === 'CE' ? row.cePremium : row.pePremium;
  };

  const addLeg = (action, type, strike) => {
    const premium = premiumFor(type, strike);
    setLegs((prev) => [...prev, { id: LEG_ID++, action, type, strike, premium, lots: 1 }]);
  };

  const applyStrategy = (strategy) => {
    if (!data) return;
    const built = strategy.build(atm, step, premiumFor).map((l) => ({
      id: LEG_ID++, ...l, premium: premiumFor(l.type, l.strike), lots: 1,
    }));
    setLegs(built);
  };

  const removeLeg = (id) => setLegs((prev) => prev.filter((l) => l.id !== id));
  const setLots = (id, lots) =>
    setLegs((prev) => prev.map((l) => (l.id === id ? { ...l, lots: Math.max(1, lots) } : l)));

  // --- payoff curve --------------------------------------------------------
  const payoff = useMemo(() => {
    if (!spot || legs.length === 0) return [];
    const lo = Math.round(spot * 0.9);
    const hi = Math.round(spot * 1.1);
    const points = 121;
    const out = [];
    for (let i = 0; i < points; i++) {
      const s = lo + ((hi - lo) * i) / (points - 1);
      const pnl = legs.reduce((acc, leg) => acc + legPnl(leg, s), 0);
      out.push({ spot: Math.round(s), pnl: Math.round(pnl) });
    }
    return out;
  }, [legs, spot]);

  const metrics = useMemo(() => computeMetrics(payoff, legs), [payoff, legs]);

  if (loading) {
    return <Box sx={{ display: 'flex', justifyContent: 'center', p: 8 }}><CircularProgress /></Box>;
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 0.5, flexWrap: 'wrap' }}>
        <AutoGraphIcon sx={{ fontSize: 30, color: 'primary.main' }} />
        <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 800 }}>
          Strategy Builder
        </Typography>
        {data && (
          <Chip size="small" label={`Spot ${spot.toLocaleString('en-IN')} • Expiry ${data.expiry} (${data.daysToExpiry}d)`}
            sx={{ bgcolor: 'primary.light', color: 'primary.dark', fontWeight: 700 }} />
        )}
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Build an options strategy and preview its payoff at expiry. Premiums are theoretical (Black-Scholes from live IV).
      </Typography>

      {error && (
        <Card sx={{ mb: 3, borderColor: 'error.main' }}>
          <CardContent><Typography color="error">{error}</Typography></CardContent>
        </Card>
      )}

      <Grid container spacing={3}>
        {/* LEFT: strategy templates + builder */}
        <Grid size={{ xs: 12, md: 5 }}>
          <Card sx={{ mb: 3 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1.5, fontFamily: 'Outfit, sans-serif' }}>Pre-built strategies</Typography>
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
                {STRATEGIES.map((s) => (
                  <MuiTooltip key={s.key} title={s.view} arrow>
                    <Chip label={s.name} onClick={() => applyStrategy(s)} clickable
                      sx={{ fontWeight: 600, bgcolor: 'action.hover', '&:hover': { bgcolor: 'primary.light', color: 'primary.dark' } }} />
                  </MuiTooltip>
                ))}
              </Box>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 2, fontFamily: 'Outfit, sans-serif' }}>Add a leg</Typography>
              <Stack direction="row" spacing={1.5} sx={{ mb: 2, flexWrap: 'wrap', gap: 1.5 }}>
                <ToggleButtonGroup exclusive size="small" value={form.action}
                  onChange={(e, v) => v && setForm((f) => ({ ...f, action: v }))}>
                  <ToggleButton value="BUY" sx={{ px: 2, fontWeight: 700, '&.Mui-selected': { bgcolor: 'success.main', color: '#fff', '&:hover': { bgcolor: 'success.main' } } }}>Buy</ToggleButton>
                  <ToggleButton value="SELL" sx={{ px: 2, fontWeight: 700, '&.Mui-selected': { bgcolor: 'secondary.main', color: '#fff', '&:hover': { bgcolor: 'secondary.main' } } }}>Sell</ToggleButton>
                </ToggleButtonGroup>
                <ToggleButtonGroup exclusive size="small" value={form.type}
                  onChange={(e, v) => v && setForm((f) => ({ ...f, type: v }))}>
                  <ToggleButton value="CE" sx={{ px: 2, fontWeight: 700 }}>Call</ToggleButton>
                  <ToggleButton value="PE" sx={{ px: 2, fontWeight: 700 }}>Put</ToggleButton>
                </ToggleButtonGroup>
              </Stack>
              <Stack direction="row" spacing={1.5} alignItems="center">
                <TextField select size="small" label="Strike" value={form.strike}
                  onChange={(e) => setForm((f) => ({ ...f, strike: Number(e.target.value) }))}
                  sx={{ minWidth: 130 }}>
                  {strikes.map((k) => (
                    <MenuItem key={k} value={k}>{k}{k === atm ? '  (ATM)' : ''}</MenuItem>
                  ))}
                </TextField>
                <Chip label={`LTP ₹${premiumFor(form.type, form.strike).toFixed(2)}`} sx={{ fontWeight: 700 }} />
                <Button variant="contained" startIcon={<AddIcon />}
                  onClick={() => addLeg(form.action, form.type, form.strike)}>Add</Button>
              </Stack>
            </CardContent>
          </Card>

          {legs.length > 0 && (
            <Card sx={{ mt: 3 }}>
              <CardContent>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1.5 }}>
                  <Typography variant="h6" sx={{ fontFamily: 'Outfit, sans-serif' }}>Legs ({legs.length})</Typography>
                  <Button size="small" color="inherit" onClick={() => setLegs([])}>Clear all</Button>
                </Box>
                <Stack divider={<Divider flexItem />} spacing={1}>
                  {legs.map((leg) => (
                    <Box key={leg.id} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.5 }}>
                      <Chip size="small" label={leg.action}
                        sx={{ fontWeight: 700, minWidth: 52, bgcolor: leg.action === 'BUY' ? 'rgba(0,179,134,0.12)' : 'rgba(224,72,61,0.12)', color: leg.action === 'BUY' ? 'success.main' : 'secondary.main' }} />
                      <Typography sx={{ fontWeight: 700, minWidth: 96 }}>{leg.strike} {leg.type}</Typography>
                      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 70 }}>₹{leg.premium.toFixed(2)}</Typography>
                      <TextField size="small" type="number" value={leg.lots}
                        onChange={(e) => setLots(leg.id, Number(e.target.value))}
                        label="Lots" sx={{ width: 84 }} inputProps={{ min: 1 }} />
                      <IconButton size="small" onClick={() => removeLeg(leg.id)} sx={{ ml: 'auto' }}>
                        <DeleteOutlineIcon fontSize="small" />
                      </IconButton>
                    </Box>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          )}
        </Grid>

        {/* RIGHT: payoff chart + metrics */}
        <Grid size={{ xs: 12, md: 7 }}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 2, fontFamily: 'Outfit, sans-serif' }}>Payoff at expiry</Typography>
              {legs.length === 0 ? (
                <Box sx={{ height: 320, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1, color: 'text.secondary' }}>
                  <AutoGraphIcon sx={{ fontSize: 44, color: 'text.disabled' }} />
                  <Typography>Pick a pre-built strategy or add a leg to see the payoff.</Typography>
                </Box>
              ) : (
                <>
                  <Box sx={{ width: '100%', height: 320 }}>
                    <ResponsiveContainer>
                      <AreaChart data={payoff} margin={{ top: 10, right: 16, left: 0, bottom: 0 }}>
                        <defs>
                          <linearGradient id="pnlUp" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor="#00b386" stopOpacity={0.35} />
                            <stop offset="100%" stopColor="#00b386" stopOpacity={0.02} />
                          </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" stroke="#e9eaf2" />
                        <XAxis dataKey="spot" stroke="#6b7185" tick={{ fontSize: 12 }}
                          tickFormatter={(v) => v.toLocaleString('en-IN')} />
                        <YAxis stroke="#6b7185" tick={{ fontSize: 12 }}
                          tickFormatter={(v) => `${Math.round(v / 1000)}k`} />
                        <Tooltip
                          contentStyle={{ backgroundColor: '#fff', borderColor: '#e9eaf2', borderRadius: 10, boxShadow: '0 4px 12px rgba(16,24,40,0.08)' }}
                          formatter={(v) => [fmtInr(v), 'P&L']}
                          labelFormatter={(v) => `Spot ${Number(v).toLocaleString('en-IN')}`} />
                        <ReferenceLine y={0} stroke="#9aa0b4" />
                        <ReferenceLine x={payoff.reduce((c, p) => Math.abs(p.spot - spot) < Math.abs(c - spot) ? p.spot : c, payoff[0]?.spot)}
                          stroke="#5a3df5" strokeDasharray="4 4" label={{ value: 'Spot', fill: '#5a3df5', fontSize: 11, position: 'top' }} />
                        <Area type="monotone" dataKey="pnl" stroke="#00b386" strokeWidth={2} fill="url(#pnlUp)" />
                      </AreaChart>
                    </ResponsiveContainer>
                  </Box>

                  <Grid container spacing={2} sx={{ mt: 1 }}>
                    <Metric label="Max Profit" value={metrics.maxProfitLabel} color="success.main" />
                    <Metric label="Max Loss" value={metrics.maxLossLabel} color="secondary.main" />
                    <Metric label={metrics.net >= 0 ? 'Net Credit' : 'Net Debit'} value={fmtInr(Math.abs(metrics.net))} />
                    <Metric label="Breakeven(s)" value={metrics.breakevenLabel} />
                  </Grid>
                </>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

const Metric = ({ label, value, color }) => (
  <Grid size={{ xs: 6, sm: 3 }}>
    <Typography variant="caption" color="text.secondary">{label}</Typography>
    <Typography variant="h6" sx={{ fontWeight: 800, color: color || 'text.primary' }}>{value}</Typography>
  </Grid>
);

// nearest ATM strike to spot from the premium list
function nearestAtm(data) {
  if (!data?.premiums?.length) return '';
  return data.premiums.reduce((c, p) =>
    Math.abs(p.strike - data.spot) < Math.abs(c - data.spot) ? p.strike : c, data.premiums[0].strike);
}

function computeMetrics(payoff, legs) {
  if (payoff.length === 0) {
    return { maxProfitLabel: '—', maxLossLabel: '—', net: 0, breakevenLabel: '—' };
  }
  const pnls = payoff.map((p) => p.pnl);
  const maxProfit = Math.max(...pnls);
  const maxLoss = Math.min(...pnls);

  // Detect open-ended payoff (still rising/falling at the edges = unlimited).
  const risingAtRightEdge = pnls[pnls.length - 1] > pnls[pnls.length - 2];
  const fallingAtLeftEdge = pnls[0] > pnls[1];
  const unlimitedProfit = (risingAtRightEdge && maxProfit === pnls[pnls.length - 1]) ||
    (fallingAtLeftEdge && maxProfit === pnls[0]);
  const risingAtLeft = pnls[0] < pnls[1];
  const fallingAtRight = pnls[pnls.length - 1] < pnls[pnls.length - 2];
  const unlimitedLoss = (fallingAtRight && maxLoss === pnls[pnls.length - 1]) ||
    (risingAtLeft && maxLoss === pnls[0] && false); // left-edge handled below

  // net debit/credit (points * lot)
  const net = legs.reduce((acc, leg) => {
    const sign = leg.action === 'BUY' ? -1 : 1; // buy pays, sell receives
    return acc + sign * leg.premium * leg.lots * LOT_SIZE;
  }, 0);

  // breakevens: sign changes along the curve
  const bes = [];
  for (let i = 1; i < payoff.length; i++) {
    const a = payoff[i - 1], b = payoff[i];
    if ((a.pnl <= 0 && b.pnl > 0) || (a.pnl >= 0 && b.pnl < 0)) {
      const t = a.pnl / (a.pnl - b.pnl);
      bes.push(Math.round(a.spot + t * (b.spot - a.spot)));
    }
  }

  return {
    maxProfitLabel: unlimitedProfit ? 'Unlimited' : fmtInr(maxProfit),
    maxLossLabel: unlimitedLoss ? 'Unlimited' : fmtInr(maxLoss),
    net,
    breakevenLabel: bes.length ? bes.map((b) => b.toLocaleString('en-IN')).join(' / ') : '—',
  };
}

export default StrategyBuilder;
