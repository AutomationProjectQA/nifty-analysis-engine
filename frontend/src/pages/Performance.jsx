import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { Box, Card, CardContent, Typography, Button, TextField, CircularProgress, Alert, Chip } from '@mui/material';
import Grid from '@mui/material/Grid';
import api from '../api/client';

const fmtInr = (v) =>
  typeof v === 'number'
    ? `${v < 0 ? '-' : ''}₹${Math.abs(v).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`
    : '—';

const fmtDuration = (secs) => {
  if (!secs || secs <= 0) return '—';
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
};

const StatCard = ({ label, value, color }) => (
  <Grid size={{ xs: 6, sm: 4, md: 2 }}>
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>{label}</Typography>
        <Typography variant="h6" sx={{ fontWeight: 700, color: color || 'text.primary', mt: 0.5 }}>
          {value}
        </Typography>
      </CardContent>
    </Card>
  </Grid>
);

StatCard.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.oneOfType([PropTypes.string, PropTypes.number]),
  color: PropTypes.string,
};

const Performance = () => {
  // --- Live performance summary ---
  const [summary, setSummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState(null);

  const fetchSummary = async () => {
    setSummaryLoading(true);
    setSummaryError(null);
    try {
      const res = await api.get('/api/v1/analytics/summary');
      setSummary(res.data);
    } catch (err) {
      setSummaryError('Could not load performance summary. Is the backend running?');
    } finally {
      setSummaryLoading(false);
    }
  };

  useEffect(() => {
    fetchSummary();
  }, []);

  // --- Backtest runner ---
  const today = new Date().toISOString().slice(0, 10);
  // Default the range to the last 30 days so the date inputs show real values
  // instead of rendering as empty/broken-looking fields.
  const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const [startDate, setStartDate] = useState(thirtyDaysAgo);
  const [endDate, setEndDate] = useState(today);
  const [backtest, setBacktest] = useState(null);
  const [btLoading, setBtLoading] = useState(false);
  const [btError, setBtError] = useState(null);

  const runBacktest = async () => {
    if (!startDate || !endDate) {
      setBtError('Please pick both a start and end date.');
      return;
    }
    setBtLoading(true);
    setBtError(null);
    setBacktest(null);
    try {
      const res = await api.post('/api/v1/analytics/backtest/run', null, {
        params: { start: `${startDate}T00:00:00`, end: `${endDate}T23:59:59` },
        timeout: 60000,
      });
      setBacktest(res.data);
    } catch (err) {
      setBtError('Backtest request failed. Check the date range and backend logs.');
    } finally {
      setBtLoading(false);
    }
  };

  const hasTrades = summary && summary.totalTrades > 0;

  return (
    <Box>
      <Typography variant="h5" sx={{ fontWeight: 700, mb: 3 }}>Performance &amp; Backtesting</Typography>

      {/* Live summary */}
      <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1.5, color: 'text.secondary' }}>
        Live Trade Performance (all time)
      </Typography>

      {summaryLoading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}><CircularProgress /></Box>
      ) : summaryError ? (
        <Alert severity="error" sx={{ mb: 3 }} action={<Button color="inherit" size="small" onClick={fetchSummary}>Retry</Button>}>
          {summaryError}
        </Alert>
      ) : !hasTrades ? (
        <Alert severity="info" sx={{ mb: 3 }}>No resolved trades yet. Stats will appear here once signals hit their target or stop-loss.</Alert>
      ) : (
        <Grid container spacing={2} sx={{ mb: 4 }}>
          <StatCard label="Total Trades" value={summary.totalTrades} />
          <StatCard label="Win Rate" value={`${summary.winRatePercentage ?? 0}%`}
            color={(summary.winRatePercentage ?? 0) >= 50 ? 'primary.main' : 'secondary.main'} />
          <StatCard label="Net P&L" value={fmtInr(summary.totalProfitLossInr)}
            color={summary.totalProfitLossInr >= 0 ? 'primary.main' : 'secondary.main'} />
          <StatCard label="Target-2 Hits" value={summary.target2Hits} color="primary.main" />
          <StatCard label="Stop-Loss Hits" value={summary.stopLossHits} color="secondary.main" />
          <StatCard label="Expired" value={summary.expiredTrades} color="text.secondary" />
        </Grid>
      )}

      {/* Backtest runner */}
      <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1.5, mt: 2, color: 'text.secondary' }}>
        Run a Backtest
      </Typography>
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
            <TextField
              label="Start date" type="date" size="small"
              InputLabelProps={{ shrink: true }}
              inputProps={{ max: endDate || today }}
              value={startDate} onChange={(e) => setStartDate(e.target.value)}
            />
            <TextField
              label="End date" type="date" size="small"
              InputLabelProps={{ shrink: true }}
              inputProps={{ min: startDate, max: today }}
              value={endDate} onChange={(e) => setEndDate(e.target.value)}
            />
            <Button variant="contained" onClick={runBacktest} disabled={btLoading}>
              {btLoading ? <CircularProgress size={22} color="inherit" /> : 'Run Backtest'}
            </Button>
          </Box>
          <Typography variant="caption" sx={{ color: 'text.secondary', mt: 1, display: 'block' }}>
            Replays historical snapshots through the live decision logic, with brokerage, slippage &amp; theta modeled.
          </Typography>
        </CardContent>
      </Card>

      {btError && <Alert severity="error" sx={{ mb: 3 }}>{btError}</Alert>}

      {backtest && backtest.status === 'FAILED' && (
        <Alert severity="warning" sx={{ mb: 3 }}>{backtest.error || 'Backtest could not run.'}</Alert>
      )}

      {backtest && backtest.status === 'SUCCESS' && (
        <Box>
          <Chip label="Backtest complete" color="success" size="small" sx={{ mb: 2 }} />
          <Grid container spacing={2}>
            <StatCard label="Signals" value={backtest.totalSignals} />
            <StatCard label="Win Rate" value={`${backtest.winRatePercentage ?? 0}%`}
              color={(backtest.winRatePercentage ?? 0) >= 50 ? 'primary.main' : 'secondary.main'} />
            <StatCard label="Net P&L" value={fmtInr(backtest.netPnlInr)}
              color={backtest.netPnlInr >= 0 ? 'primary.main' : 'secondary.main'} />
            <StatCard label="Gross P&L" value={fmtInr(backtest.grossPnlInr)} />
            <StatCard label="Costs" value={fmtInr(backtest.totalCostsInr)} color="secondary.main" />
            <StatCard label="Avg Hold" value={fmtDuration(backtest.avgHoldingSeconds)} />
            <StatCard label="Target-2 Hits" value={backtest.target2Hits} color="primary.main" />
            <StatCard label="Stop-Loss Hits" value={backtest.stopLossHits} color="secondary.main" />
            <StatCard label="Expired" value={backtest.expired} color="text.secondary" />
            <StatCard label="Target1 Touches" value={backtest.target1Touches} />
          </Grid>
        </Box>
      )}
    </Box>
  );
};

export default Performance;
