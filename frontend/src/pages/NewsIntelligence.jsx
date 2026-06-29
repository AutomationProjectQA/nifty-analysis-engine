import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, List, ListItem, ListItemIcon, ListItemText, Divider, CircularProgress, Chip, Alert, Button } from '@mui/material';
import Grid from '@mui/material/Grid';
import NewspaperIcon from '@mui/icons-material/Newspaper';
import ArrowRightIcon from '@mui/icons-material/ArrowRight';
import ReactMarkdown from 'react-markdown';
import api from '../api/client';

// Mock news items if backend is down
const mockNews = [
  {
    id: 1,
    title: "Top 5 Events Impacting Nifty Today",
    summary: `1. **FII Activity Reinforces Bullish Bias:** Foreign Institutional Investors (FIIs) registered net purchases of **+1,250 Cr** today, supporting market liquidity and validating breakouts.
2. **US Dow Futures Cues:** Strong gains in US futures (+180 pts) drove opening gains and improved risk appetite in early trading.
3. **Brent Crude Volatility:** Brent crude trading stable at **78 USD/bbl** mitigates fiscal inflation fears for oil importers like India.
4. **Dollar Index Consolidates:** DXY holding near **101.5** eases capital outflow pressures from emerging market equities.
5. **Options Concentration support:** Heavy put writing building up at 23,500 indicates a strong price floor, which kept the index afloat against profit booking.`,
    publishedAt: "2026-06-12T15:45:00",
    importance: "HIGH",
    sourceUrl: "https://finance.yahoo.com"
  }
];

// Require an id and ensure a usable summary/title so a malformed item can't break the list.
const sanitizeNews = (rows) =>
  (rows || [])
    .filter((n) => n && n.id != null)
    .map((n) => ({ ...n, title: n.title || 'Market update', summary: n.summary || '' }));

// Safe published date (no "Invalid Date").
const fmtNewsDate = (value) => {
  const d = value ? new Date(value) : null;
  return d && !isNaN(d.getTime())
    ? d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
    : '—';
};

const NewsIntelligence = () => {
  const [newsList, setNewsList] = useState(mockNews);
  const [loading, setLoading] = useState(false);
  const [live, setLive] = useState(null); // null=loading, true=live, false=demo fallback
  const [genMsg, setGenMsg] = useState(null); // { severity, text } feedback after a generate

  const fetchNews = async () => {
    setLoading(true);
    try {
      const response = await api.get('/api/v1/news/today');
      setNewsList(sanitizeNews(response.data)); // trust backend even when empty (genuine "no news yet")
      setLive(true);
    } catch (e) {
      console.warn("Backend offline, using local simulated news vlogs.", e.message);
      setLive(false); // keep mock data already in state
    } finally {
      setLoading(false);
    }
  };

  const [generating, setGenerating] = useState(false);
  const generateNews = async () => {
    setGenerating(true);
    setGenMsg(null);
    try {
      await api.post('/api/v1/news/generate', null, { timeout: 60000 });
      await fetchNews();
      setGenMsg({ severity: 'success', text: 'Fresh news intelligence generated.' });
    } catch (e) {
      console.warn('News generation failed.', e.message);
      setGenMsg({ severity: 'error', text: 'News generation failed. Check the backend logs and try again.' });
    } finally {
      setGenerating(false);
    }
  };

  useEffect(() => {
    fetchNews();
  }, []);

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 3, flexWrap: 'wrap' }}>
        <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
          <NewspaperIcon sx={{ fontSize: 35, color: 'primary.main' }} />
          Market News Intelligence
        </Typography>
        {live === false && (
          <Chip label="Demo data — backend unreachable" size="small"
            sx={{ bgcolor: 'rgba(255,179,0,0.12)', color: '#ffb300', border: '1px solid rgba(255,179,0,0.3)', fontWeight: 600 }} />
        )}
        <Button variant="outlined" size="small" onClick={generateNews} disabled={generating} sx={{ ml: 'auto' }}>
          {generating ? <CircularProgress size={18} color="inherit" /> : 'Generate now'}
        </Button>
      </Box>

      {genMsg && (
        <Alert severity={genMsg.severity} sx={{ mb: 3 }} onClose={() => setGenMsg(null)}>
          {genMsg.text}
        </Alert>
      )}

      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', my: 10 }}>
          <CircularProgress />
        </Box>
      ) : live === true && newsList.length === 0 ? (
        <Alert severity="info">No market news published yet today.</Alert>
      ) : (
        <Grid container spacing={3}>
          {newsList.map((news) => {
            const timeString = fmtNewsDate(news.publishedAt);
            return (
              <Grid key={news.id} size={12}>
                <Card>
                  <CardContent sx={{ p: 4 }}>
                    
                    {/* Header */}
                    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 3, flexWrap: 'wrap', gap: 1 }}>
                      <Typography variant="h5" sx={{ fontWeight: 700, fontFamily: 'Outfit, sans-serif' }}>
                        {news.title}
                      </Typography>
                      <Box sx={{ display: 'flex', gap: 1 }}>
                        <Chip label={news.importance} size="small" color={news.importance === 'HIGH' ? 'secondary' : 'default'} sx={{ fontWeight: 700 }} />
                        <Chip label={timeString} size="small" variant="outlined" sx={{ fontWeight: 600 }} />
                      </Box>
                    </Box>
                    <Divider sx={{ mb: 3 }} />

                    {/* News Bullets using Markdown */}
                    <Box className="markdown-news-body" sx={{ 
                      color: 'text.secondary',
                      '& ol': { pl: 0, listStyle: 'none' },
                      '& li': { mb: 3, pl: 0, position: 'relative', lineHeight: 1.7, fontSize: '0.95rem' },
                      '& strong': { color: 'text.primary', fontWeight: 600 }
                    }}>
                      <ReactMarkdown>{news.summary}</ReactMarkdown>
                    </Box>

                    {/* Source link for real headlines pulled from the news feeds. */}
                    {news.sourceUrl && /^https?:\/\//.test(news.sourceUrl) && (
                      <Box sx={{ mt: 2 }}>
                        <Button
                          variant="text"
                          size="small"
                          href={news.sourceUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          endIcon={<ArrowRightIcon />}
                        >
                          Read full story
                        </Button>
                      </Box>
                    )}

                  </CardContent>
                </Card>
              </Grid>
            );
          })}
        </Grid>
      )}
    </Box>
  );
};

export default NewsIntelligence;
