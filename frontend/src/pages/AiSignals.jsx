import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, Accordion, AccordionSummary, AccordionDetails, Chip, CircularProgress, Button, Alert } from '@mui/material';
import Grid from '@mui/material/Grid';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import BoltIcon from '@mui/icons-material/Bolt';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import GavelIcon from '@mui/icons-material/Gavel';
import api from '../api/client';
import { subscribe } from '../api/marketStream';

// Mock signals if backend is down
const mockSignals = [
  {
    id: 101,
    signalTime: "2026-06-12T10:15:00",
    signalType: "BUY_CE",
    strike: 23500,
    entry: 150.0,
    stopLoss: 135.0,
    target1: 165.0,
    target2: 180.0,
    confidence: 82.5,
    status: "ACTIVE",
    thesis: "The trade thesis for BUY_CE on strike 23500 is justified by robust trend confirmation and substantial Put writing support acting as a price floor. Furthermore, price action is consolidating above the key 20-period EMA breakout level. This aligns with structural parameters for bullish momentum."
  },
  {
    id: 102,
    signalTime: "2026-06-12T09:20:00",
    signalType: "BUY_PE",
    strike: 23600,
    entry: 145.0,
    stopLoss: 130.0,
    target1: 160.0,
    target2: 175.0,
    confidence: 68.0,
    status: "TARGET1",
    thesis: "Aggressive Call writing at the 23600 resistance wall coupled with a negative divergence in short-term momentum triggers this bearish entry. Price has broken below the VWAP support level on the 15m chart, confirming immediate downside targets."
  },
  {
    id: 103,
    signalTime: "2026-06-11T14:40:00",
    signalType: "BUY_CE",
    strike: 23400,
    entry: 150.0,
    stopLoss: 135.0,
    target1: 165.0,
    target2: 180.0,
    confidence: 88.0,
    status: "TARGET2",
    thesis: "A clean retest of the daily support zone on high volume provides a high probability breakout candidate. Support at 23400 is reinforced by FII net purchasing sentiment."
  }
];

const AiSignals = () => {
  const [signals, setSignals] = useState(mockSignals);
  const [activeTab, setActiveTab] = useState('ALL'); // ALL, ACTIVE, EXPIRED
  const [live, setLive] = useState(null); // null=loading, true=live backend, false=demo fallback

  const fetchSignals = async () => {
    try {
      const response = await api.get('/api/v1/signals');
      setSignals(response.data || []); // trust backend even when empty (genuine "no signals yet")
      setLive(true);
    } catch (e) {
      console.warn("Backend down, showing simulated option signals.", e.message);
      setLive(false); // keep mock data already in state
    }
  };

  useEffect(() => {
    fetchSignals(); // initial paint via REST
    // Live updates pushed over WebSocket — no more polling.
    const unsub = subscribe('/topic/signals', (data) => {
      setSignals(data || []);
      setLive(true);
    });
    return unsub;
  }, []);

  const getStatusColor = (status) => {
    switch (status) {
      case 'ACTIVE': return { bg: 'rgba(38, 166, 154, 0.1)', color: 'primary.main', label: 'Active' };
      case 'TARGET1': return { bg: 'rgba(38, 166, 154, 0.2)', color: 'primary.main', label: 'Target 1 Hit' };
      case 'TARGET2': return { bg: 'rgba(38, 166, 154, 0.3)', color: 'primary.main', label: 'Target 2 Hit' };
      case 'STOP_LOSS': return { bg: 'rgba(239, 83, 80, 0.1)', color: 'secondary.main', label: 'SL Hit' };
      case 'EXPIRED': return { bg: 'rgba(255, 179, 0, 0.1)', color: 'warning.main', label: 'Expired' };
      default: return { bg: 'rgba(255,255,255,0.06)', color: 'text.secondary' };
    }
  };

  const filteredSignals = signals.filter(s => {
    if (activeTab === 'ACTIVE') return s.status === 'ACTIVE';
    if (activeTab === 'EXPIRED') return s.status !== 'ACTIVE';
    return true;
  });

  // Precompute tab counts once instead of re-filtering inside each button's label.
  const tabCounts = {
    ALL: signals.length,
    ACTIVE: signals.filter(s => s.status === 'ACTIVE').length,
    EXPIRED: signals.filter(s => s.status !== 'ACTIVE').length,
  };

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700 }}>
          AI Signal Engine
        </Typography>
        {live === false && (
          <Chip label="Demo data — backend unreachable" size="small"
            sx={{ bgcolor: 'rgba(255,179,0,0.12)', color: '#ffb300', border: '1px solid rgba(255,179,0,0.3)', fontWeight: 600 }} />
        )}
      </Box>

      {/* Tabs / Filters */}
      <Box sx={{ display: 'flex', gap: 2, mb: 4, flexWrap: 'wrap' }}>
        {['ALL', 'ACTIVE', 'EXPIRED'].map((tab) => (
          <Button
            key={tab}
            variant={activeTab === tab ? 'contained' : 'outlined'}
            color={activeTab === tab ? 'primary' : 'inherit'}
            onClick={() => setActiveTab(tab)}
            sx={{
              fontWeight: 600,
              bgcolor: activeTab === tab ? 'primary.main' : 'transparent',
              borderColor: activeTab === tab ? 'primary.main' : 'divider',
              '&:hover': {
                borderColor: 'primary.main',
                bgcolor: activeTab === tab ? 'primary.main' : 'rgba(38, 166, 154, 0.05)'
              }
            }}
          >
            {tab} Signals ({tabCounts[tab]})
          </Button>
        ))}
      </Box>

      {/* Loading / empty states */}
      {live === null && (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}><CircularProgress /></Box>
      )}
      {live === true && filteredSignals.length === 0 && (
        <Alert severity="info">No {activeTab !== 'ALL' ? activeTab.toLowerCase() + ' ' : ''}signals yet. New signals appear here as the engine generates them.</Alert>
      )}

      {/* Signals Grid */}
      <Grid container spacing={3}>
        {filteredSignals.map((sig) => {
          const isCe = sig.signalType === 'BUY_CE';
          const statColor = getStatusColor(sig.status);
          const timeString = new Date(sig.signalTime).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
          const dateString = new Date(sig.signalTime).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

          return (
            <Grid key={sig.id} size={{ xs: 12, md: 6 }}>
              <Card sx={{ borderLeft: 5, borderColor: isCe ? 'primary.main' : 'secondary.main', height: '100%' }}>
                <CardContent sx={{ pb: '16px !important' }}>

                  {/* Card Header */}
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, gap: 1, flexWrap: 'wrap' }}>
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Chip
                        label={sig.signalType}
                        sx={{
                          bgcolor: isCe ? 'rgba(38, 166, 154, 0.15)' : 'rgba(239, 83, 80, 0.15)',
                          color: isCe ? 'primary.main' : 'secondary.main',
                          fontWeight: 700,
                          borderRadius: 1
                        }}
                      />
                      <Typography variant="h6" sx={{ fontWeight: 700, fontFamily: 'Outfit, sans-serif' }}>
                        NIFTY {sig.strike} {isCe ? 'CE' : 'PE'}
                      </Typography>
                    </Box>
                    <Chip
                      label={statColor.label || sig.status}
                      sx={{ bgcolor: statColor.bg, color: statColor.color, fontWeight: 600, borderRadius: 1 }}
                    />
                  </Box>

                  {/* Pricing levels grid */}
                  <Grid container spacing={2} sx={{ mb: 3, bgcolor: 'action.hover', p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider' }}>
                    <Grid size={4} sx={{ textAlign: 'center' }}>
                      <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>ENTRY</Typography>
                      <Typography variant="h6" sx={{ fontWeight: 700, mt: 0.5, color: 'text.primary' }}>{sig.entry.toFixed(2)}</Typography>
                    </Grid>
                    <Grid size={4} sx={{ textAlign: 'center' }}>
                      <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>TARGET 1</Typography>
                      <Typography variant="h6" sx={{ fontWeight: 700, mt: 0.5, color: 'primary.main' }}>{sig.target1.toFixed(2)}</Typography>
                    </Grid>
                    <Grid size={4} sx={{ textAlign: 'center' }}>
                      <Typography variant="body2" sx={{ color: 'text.secondary', fontWeight: 600 }}>STOP LOSS</Typography>
                      <Typography variant="h6" sx={{ fontWeight: 700, mt: 0.5, color: 'secondary.main' }}>{sig.stopLoss.toFixed(2)}</Typography>
                    </Grid>
                  </Grid>

                  {/* Meter / Time Details */}
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2, gap: 1, flexWrap: 'wrap' }}>

                    {/* Time details */}
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, color: 'text.secondary' }}>
                      <AccessTimeIcon fontSize="small" />
                      <Typography variant="body2" sx={{ fontWeight: 500 }}>
                        {dateString} at {timeString}
                      </Typography>
                    </Box>

                    {/* Confidence score indicator */}
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <Box sx={{ position: 'relative', display: 'inline-flex' }}>
                        <CircularProgress
                          variant="determinate"
                          value={sig.confidence}
                          size={28}
                          thickness={5}
                          sx={{ color: sig.confidence >= 80 ? 'primary.main' : 'warning.main' }}
                        />
                        <Box
                          sx={{
                            top: 0, left: 0, bottom: 0, right: 0,
                            position: 'absolute', display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                          }}
                        >
                          <BoltIcon sx={{ fontSize: 16, color: sig.confidence >= 80 ? 'primary.main' : 'warning.main' }} />
                        </Box>
                      </Box>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>
                        {sig.confidence.toFixed(1)}% Conf
                      </Typography>
                    </Box>

                  </Box>

                  {/* Gemini AI Thesis Accordion */}
                  <Accordion disableGutters sx={{ bgcolor: 'transparent', boxShadow: 'none', '&:before': { display: 'none' }, border: '1px solid', borderColor: 'divider', borderRadius: '8px !important' }}>
                    <AccordionSummary expandIcon={<ExpandMoreIcon sx={{ color: 'primary.main' }} />}>
                      <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main', display: 'flex', alignItems: 'center', gap: 0.5 }}>
                        <GavelIcon sx={{ fontSize: 18 }} />
                        AI Analysis Thesis
                      </Typography>
                    </AccordionSummary>
                    <AccordionDetails sx={{ pt: 0, borderTop: '1px solid', borderColor: 'divider' }}>
                      <Typography variant="body2" sx={{ mt: 1.5, color: 'text.secondary', lineHeight: 1.6 }}>
                        {sig.thesis || "No detailed thesis registered for this trade signal."}
                      </Typography>
                    </AccordionDetails>
                  </Accordion>

                </CardContent>
              </Card>
            </Grid>
          );
        })}
      </Grid>
    </Box>
  );
};

export default AiSignals;
