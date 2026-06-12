import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, List, ListItem, ListItemIcon, ListItemText, Divider, CircularProgress, Chip } from '@mui/material';
import NewspaperIcon from '@mui/icons-material/Newspaper';
import ArrowRightIcon from '@mui/icons-material/ArrowRight';
import ReactMarkdown from 'react-markdown';
import axios from 'axios';

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

const NewsIntelligence = () => {
  const [newsList, setNewsList] = useState(mockNews);
  const [loading, setLoading] = useState(false);

  const fetchNews = async () => {
    setLoading(true);
    try {
      const response = await axios.get('http://localhost:8080/api/v1/news/today');
      if (response.data && response.data.length > 0) {
        setNewsList(response.data);
      }
    } catch (e) {
      console.warn("Backend offline, using local simulated news vlogs.", e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchNews();
  }, []);

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3, display: 'flex', alignItems: 'center', gap: 1 }}>
        <NewspaperIcon sx={{ fontSize: 35, color: 'primary.main' }} />
        Market News Intelligence
      </Typography>

      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', my: 10 }}>
          <CircularProgress />
        </Box>
      ) : (
        <Grid container spacing={3}>
          {newsList.map((news) => {
            const timeString = new Date(news.publishedAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
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
                      '& strong': { color: '#ffffff', fontWeight: 600 }
                    }}>
                      <ReactMarkdown>{news.summary}</ReactMarkdown>
                    </Box>

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
