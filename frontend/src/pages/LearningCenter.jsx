import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, Button, Dialog, DialogTitle, DialogContent, DialogActions, TextField, Chip, CircularProgress } from '@mui/material';
import Grid from '@mui/material/Grid';
import ReactMarkdown from 'react-markdown';
import SchoolIcon from '@mui/icons-material/School';
import SearchIcon from '@mui/icons-material/Search';
import SearchOffIcon from '@mui/icons-material/SearchOff';
import DescriptionIcon from '@mui/icons-material/Description';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import api from '../api/client';

import AdSenseSlot from '../components/AdSenseSlot';

// Mock articles if backend is down
const mockArticles = [
  {
    slug: 'understanding-open-interest',
    title: 'What is Open Interest (OI) & How to Use It?',
    summary: 'Learn the fundamentals of Open Interest (OI) and how options writers build positions to create key market supports and resistance barriers.',
    category: 'Options Basics',
    content: `Open Interest (OI) represents the total number of outstanding derivative contracts, such as options or futures, that have not been settled. In option trading, understanding OI is crucial because it indicates where market participants are committing capital.

### Call vs Put Open Interest
- **Call Open Interest (CE OI):** High concentration at a particular strike indicates heavy call writing. Option sellers expect the market to remain below this level, creating a strong **resistance barrier**.
- **Put Open Interest (PE OI):** High concentration at a strike indicates heavy put writing. Option sellers expect the market to stay above this level, creating a strong **support barrier**.

### Reading OI Build-ups
By looking at price changes alongside changes in Open Interest, traders can detect four core market biases:
1. **Long Build-up:** Price rises, OI rises. Indicates new buyers entering the market with strong bullish momentum.
2. **Short Build-up:** Price falls, OI rises. Indicates sellers adding aggressive short positions.
3. **Long Unwinding:** Price falls, OI falls. Indicates buyers exiting their positions, leading to weakening momentum.
4. **Short Covering:** Price rises, OI falls. Indicates short sellers closing positions, which often leads to a fast short squeeze rally.`,
    publishedAt: "2026-06-12T00:00:00"
  },
  {
    slug: 'put-call-ratio-pcr-guide',
    title: 'Put-Call Ratio (PCR) Explained for Beginners',
    summary: 'A complete guide to interpreting Put-Call Ratio (PCR) to gauge overall market sentiment and identify potential market reversals.',
    category: 'Technical Analysis',
    content: `The Put-Call Ratio (PCR) is a popular technical sentiment indicator used by traders to measure overall market positioning. It is calculated by dividing the total volume or open interest of Put options by the total volume or open interest of Call options.

### How to Calculate PCR
**PCR (OI) = Total PE Open Interest ÷ Total CE Open Interest**

### How to Interpret PCR Values
- **PCR > 1.10 (Bullish Sentiment):** High PCR values indicate that traders are writing more Puts than Calls. This serves as a bullish indicator, suggesting that market participants expect price support levels to hold.
- **PCR < 0.70 (Bearish Sentiment):** Low PCR values indicate that Call writing dominates over Put writing. This is a bearish indicator, indicating resistance from sellers.
- **PCR between 0.70 and 1.10 (Neutral/Sideways):** Indicates balanced options writing activity with no strong directional bias. Expect sideways price action.

### Extreme PCR Reversals (Overbought/Oversold)
Extremely high PCR levels (e.g. > 1.50) can sometimes indicate that the market is oversold and a short-term bounce is likely. Conversely, extremely low PCR levels (e.g. < 0.50) can signal that the market is overbought and due for a correction.`,
    publishedAt: "2026-06-12T00:00:00"
  },
  {
    slug: 'max-pain-strike-theory',
    title: 'How Max Pain Strike Affects Expiry Day Actions',
    summary: 'Explore the Max Pain theory and learn why the market spot price tends to gravitate towards the strike where option buyers lose the most capital.',
    category: 'Advanced Strategies',
    content: `Max Pain, also known as option pain, is a theory that states that on option expiry day, the price of the underlying asset will gravitate towards the strike price where the maximum number of option buyers will lose money (i.e. options expire worthless).

### The Logic Behind Max Pain
Option writers (sellers), who are typically large institutional traders (FIIs/DIIs/Prop desks), write options to collect premiums. Because they write options with substantial capital, they have a vested interest in hedging their positions to ensure the index closes at a strike that minimizes their total payout to buyers.

### Calculating Max Pain
To find the Max Pain strike:
1. For each strike price, calculate the cumulative cash payout to option buyers if the spot price closes exactly at that strike.
2. Add the total payouts for both Calls and Puts at each level.
3. The strike price with the **minimum total payout** is identified as the **Max Pain Strike**.

### Trading with Max Pain
Traders watch the Max Pain strike as a strong magnet on expiry days. If Nifty spot is trading at 23,450 but the options chain shows Max Pain is at 23,500, there is a statistical probability that spot will drift up towards 23,500 as large institutions adjust and defend their options writing positions.`,
    publishedAt: "2026-06-12T00:00:00"
  }
];

const LearningCenter = () => {
  const [articles, setArticles] = useState(mockArticles);
  const [loading, setLoading] = useState(true);
  const [demo, setDemo] = useState(false); // true => showing built-in sample content, not the live library
  const [search, setSearch] = useState('');
  const [selectedArticle, setSelectedArticle] = useState(null);
  const [activeCategory, setActiveCategory] = useState('ALL');

  const fetchArticles = async () => {
    setLoading(true);
    try {
      const response = await api.get('/api/v1/learning/articles');
      if (response.data && response.data.length > 0) {
        setArticles(response.data);
        setDemo(false);
      } else {
        setDemo(true); // backend reachable but empty → still showing samples
      }
    } catch (e) {
      console.warn("Backend down, showing simulated articles.", e.message);
      setDemo(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchArticles();
  }, []);

  const categories = ['ALL', ...new Set(articles.map(a => a.category))];

  const filteredArticles = articles.filter(a => {
    const matchesSearch = a.title.toLowerCase().includes(search.toLowerCase()) || 
                          a.summary.toLowerCase().includes(search.toLowerCase());
    const matchesCategory = activeCategory === 'ALL' || a.category === activeCategory;
    return matchesSearch && matchesCategory;
  });

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3, display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <SchoolIcon sx={{ fontSize: 35, color: 'primary.main' }} />
        Option Learning Center
        {demo && (
          <Chip label="Sample content" size="small"
            sx={{ ml: 1, bgcolor: 'rgba(255,159,10,0.12)', color: '#ff9f0a', border: '1px solid rgba(255,159,10,0.3)', fontWeight: 600 }} />
        )}
      </Typography>

      <Grid container spacing={3}>
        {/* LEFT: category sidebar (document-portal style) */}
        <Grid size={{ xs: 12, md: 3 }}>
          <Card sx={{ position: { md: 'sticky' }, top: 88 }}>
            <CardContent sx={{ p: 1.5 }}>
              <Typography variant="overline" sx={{ px: 1, color: 'text.secondary', fontWeight: 700 }}>
                Categories
              </Typography>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, mt: 1 }}>
                {categories.map((cat) => {
                  const count = cat === 'ALL' ? articles.length : articles.filter((a) => a.category === cat).length;
                  const active = activeCategory === cat;
                  return (
                    <Box key={cat} onClick={() => setActiveCategory(cat)}
                      sx={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        px: 1.5, py: 1, borderRadius: 2, cursor: 'pointer',
                        bgcolor: active ? 'primary.light' : 'transparent',
                        color: active ? 'primary.dark' : 'text.secondary',
                        '&:hover': { bgcolor: active ? 'primary.light' : 'action.hover' },
                      }}>
                      <Typography variant="body2" sx={{ fontWeight: active ? 700 : 500 }}>
                        {cat === 'ALL' ? 'All Guides' : cat}
                      </Typography>
                      <Chip label={count} size="small" sx={{ height: 20, bgcolor: 'action.hover', color: 'text.secondary', fontWeight: 600 }} />
                    </Box>
                  );
                })}
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* RIGHT: search + document list */}
        <Grid size={{ xs: 12, md: 9 }}>
          <TextField
            fullWidth
            placeholder="Search guides…"
            size="small"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <SearchIcon sx={{ color: 'text.secondary', mr: 1 }} /> } }}
            sx={{ mb: 3, bgcolor: 'background.paper', borderRadius: 1 }}
          />

          {loading ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', p: 8 }}><CircularProgress /></Box>
          ) : filteredArticles.length === 0 ? (
            <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1.5, py: 8, color: 'text.secondary' }}>
              <SearchOffIcon sx={{ fontSize: 48, color: 'text.disabled' }} />
              <Typography variant="body1">No guides match your search.</Typography>
              {(search || activeCategory !== 'ALL') && (
                <Button variant="text" onClick={() => { setSearch(''); setActiveCategory('ALL'); }}>Clear filters</Button>
              )}
            </Box>
          ) : (
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <Typography variant="body2" sx={{ color: 'text.secondary', mb: 0.5 }}>
                {filteredArticles.length} guide{filteredArticles.length === 1 ? '' : 's'}
                {activeCategory !== 'ALL' ? ` in ${activeCategory}` : ''}
              </Typography>
              {filteredArticles.map((art) => (
                <Card key={art.slug} onClick={() => setSelectedArticle(art)}
                  sx={{
                    cursor: 'pointer', transition: 'border-color .15s, box-shadow .15s',
                    '&:hover': { borderColor: 'primary.main', boxShadow: '0 4px 14px rgba(16,24,40,0.06)' },
                  }}>
                  <CardContent sx={{ display: 'flex', alignItems: 'flex-start', gap: 2 }}>
                    <Box sx={{ width: 44, height: 44, borderRadius: 2, bgcolor: 'primary.light', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                      <DescriptionIcon sx={{ color: 'primary.main' }} />
                    </Box>
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                        <Typography variant="h6" sx={{ fontWeight: 700, fontFamily: 'Outfit, sans-serif', fontSize: '1.05rem' }}>
                          {art.title}
                        </Typography>
                        <Chip label={art.category} size="small" sx={{ height: 20, bgcolor: 'action.hover', color: 'primary.main', fontWeight: 600 }} />
                      </Box>
                      <Typography variant="body2" sx={{ color: 'text.secondary', lineHeight: 1.5 }}>{art.summary}</Typography>
                    </Box>
                    <ChevronRightIcon sx={{ color: 'text.disabled', alignSelf: 'center' }} />
                  </CardContent>
                </Card>
              ))}
            </Box>
          )}
        </Grid>
      </Grid>

      {/* Dialog for Reading Article */}
      <Dialog 
        open={Boolean(selectedArticle)} 
        onClose={() => setSelectedArticle(null)}
        maxWidth="md"
        fullWidth
        PaperProps={{
          sx: { bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 3 }
        }}
      >
        {selectedArticle && (
          <>
            <DialogTitle sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, borderBottom: '1px solid', borderColor: 'divider', pb: 2 }}>
              {selectedArticle.title}
            </DialogTitle>
            <DialogContent sx={{ mt: 2 }}>
              {/* Insert AdSense Slot inside the reading view */}
              <AdSenseSlot adSlot="learning-article-inline" />

              <Box className="markdown-report-body" sx={{
                color: 'text.primary',
                '& h1, & h2': { fontFamily: 'Outfit, sans-serif', fontSize: '1.35rem', fontWeight: 700, mt: 3, mb: 1.5, color: 'text.primary' },
                '& h3': { fontFamily: 'Outfit, sans-serif', fontSize: '1.2rem', fontWeight: 700, mt: 3, mb: 1.5, color: 'primary.main' },
                '& h4': { fontSize: '1.05rem', fontWeight: 600, mt: 2.5, mb: 1, color: 'text.primary' },
                '& p': { mb: 2, lineHeight: 1.6, color: 'text.secondary' },
                '& ul, & ol': { pl: 2.5, mb: 2 },
                '& li': { mb: 1, lineHeight: 1.6, color: 'text.secondary' },
                '& strong': { color: 'text.primary' }
              }}>
                <ReactMarkdown>{selectedArticle.content}</ReactMarkdown>
              </Box>
            </DialogContent>
            <DialogActions sx={{ borderTop: '1px solid', borderColor: 'divider', p: 2 }}>
              <Button onClick={() => setSelectedArticle(null)} variant="contained">
                Close Guide
              </Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </Box>
  );
};

export default LearningCenter;
