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
    content: `## What Open Interest Really Measures

Open Interest (OI) is the total number of derivative contracts (options or futures) that are currently OPEN — i.e. created but not yet closed, exercised, or expired. Every contract has a buyer and a seller, so one unit of OI represents one buyer matched with one seller.

A common confusion is OI vs Volume:
- **Volume** counts every trade during the day, even if a position is opened and closed within minutes. It resets to zero each session.
- **Open Interest** counts only positions that remain open. It carries over from day to day and only changes when contracts are newly created or closed.

### How OI Goes Up and Down
- A **new buyer** and a **new seller** transact → OI **increases by 1**.
- An **existing holder** closes against another closing party → OI **decreases by 1**.
- A position is simply transferred (one closes, one opens) → OI **unchanged**.

## Why OI Matters for Nifty Options

In index options, the biggest option *writers* (sellers) are institutions with deep capital. Where they write the most contracts tells you where they are willing to defend price.

- **High Call OI at a strike** = heavy call writing = sellers betting price stays *below* that strike → it acts as **RESISTANCE**.
- **High Put OI at a strike** = heavy put writing = sellers betting price stays *above* that strike → it acts as **SUPPORT**.

Example: If 24,000 CE has the highest call OI and 23,500 PE has the highest put OI, the market is broadly expected to trade between 23,500 (support) and 24,000 (resistance) until expiry.

## The Four OI Build-up Patterns

Combine the *change in price* with the *change in OI* to read intent:

- **Long Build-up** (price up, OI up): new buyers entering → **bullish**.
- **Short Build-up** (price down, OI up): new sellers entering → **bearish**.
- **Short Covering** (price up, OI down): shorts exiting, often a sharp squeeze → **bullish**.
- **Long Unwinding** (price down, OI down): longs exiting, momentum weakening → **bearish**.

### Worked Example
Suppose 23,800 CE: the option jumps from 90 to 140 and OI rises from 1.2 lakh to 2.0 lakh. Price up + OI up on a call = **Long Build-up** → traders are aggressively positioning for upside above 23,800.

The next day it falls from 140 to 70 while OI drops from 2.0 lakh to 1.1 lakh. Price down + OI down = **Long Unwinding** → the bullish bet is being abandoned; momentum is fading.

## How to Read It on the Live Option Chain

1. Scan the **OI column** on both sides — the strikes with the largest CE OI and PE OI are your resistance and support walls.
2. Watch the **OI Change column** intraday — a surge of put writing at a lower strike means support is being *built* in real time (bullish).
3. Cross-check with **price**: writing that holds as price tests the strike confirms the wall; writing that gets unwound warns the wall may break.

## Common Mistakes
- **Treating OI as direction by itself.** OI shows commitment, not direction — always pair it with price action.
- **Ignoring expiry.** OI walls are strongest near expiry and can shift quickly when far from it.
- **Forgetting OI is two-sided.** High OI is not bullish or bearish until you know who is writing (calls vs puts) and what price is doing.

## Quick FAQ
**Q: Does rising OI always mean a trend?** No — it means money is committing. The price change tells you which side.

**Q: Can support/resistance walls break?** Yes. When a heavily-written strike is breached on rising volume, trapped writers cover, often accelerating the move.`,
    publishedAt: "2026-06-12T00:00:00"
  },
  {
    slug: 'put-call-ratio-pcr-guide',
    title: 'Put-Call Ratio (PCR) Explained for Beginners',
    summary: 'A complete guide to interpreting Put-Call Ratio (PCR) to gauge overall market sentiment and identify potential market reversals.',
    category: 'Technical Analysis',
    content: `## What the Put-Call Ratio Tells You

The Put-Call Ratio (PCR) is a sentiment gauge that compares how many Put options exist relative to Call options. It answers a simple question: *are traders positioning more for downside protection (puts) or upside (calls)?*

There are two common versions:
- **PCR (OI):** total Put Open Interest divided by total Call Open Interest. Best for gauging positioning that persists.
- **PCR (Volume):** total Put volume divided by total Call volume. More reactive, better for intraday shifts.

### The Formula (plain text)

    PCR (OI) = Total Put OI / Total Call OI

If total Put OI = 80,00,000 and total Call OI = 64,00,000, then PCR = 80 / 64 = **1.25**.

## How to Interpret PCR Levels

- **PCR > 1.10 — Bullish:** more put writing than call writing. Put writers profit if the market stays up, so heavy put writing signals confidence in support.
- **PCR 0.70 to 1.10 — Neutral / Sideways:** balanced writing, no strong directional edge. Expect range-bound action.
- **PCR < 0.70 — Bearish:** call writing dominates. Sellers expect resistance to cap any rally.

### The Contrarian Twist (Extremes)

PCR is also a *contrarian* indicator at extremes, because crowded positioning tends to reverse:
- **PCR > 1.50:** market may be over-hedged / oversold → a relief bounce becomes likely as put writers saturate.
- **PCR < 0.50:** market may be over-optimistic / overbought → a pullback becomes likely.

The skill is distinguishing a *healthy* high PCR (steady support building) from an *extreme* one (exhaustion). Context — trend, VIX, and event risk — decides which.

## A Worked Intraday Scenario

Nifty opens flat. Through the morning, PCR (OI) climbs from 0.95 to 1.30 as traders aggressively write the 23,500 and 23,400 puts. Reading: support is being *built* beneath the market → bias tilts bullish. If instead PCR fell from 1.05 to 0.65 on heavy call writing overhead, the bias flips bearish.

## How to Use PCR on This Platform

1. Read the **Overall PCR** card on the Option Chain page for the day's positioning.
2. Track its *direction* over the session, not just the absolute number — a rising PCR is often more informative than its level.
3. Combine with **Max Pain** and the **OI walls**: PCR for sentiment, OI walls for the levels, Max Pain for the expiry magnet.

## Common Mistakes
- **Using PCR in isolation.** It is sentiment, not a trade trigger. Confirm with price, trend, and OI build-up.
- **Confusing PCR(OI) and PCR(Volume).** They can diverge; know which one you are reading.
- **Forgetting the contrarian zone.** A very high PCR is not infinitely bullish — past an extreme it flips meaning.

## Quick FAQ
**Q: What is a "normal" Nifty PCR?** Roughly 0.8 to 1.3 on most days; treat moves outside that band as notable.

**Q: Should I buy when PCR is high?** Not mechanically. A high-and-rising PCR supports a bullish lean; a high-and-extreme PCR warns of a reversal.`,
    publishedAt: "2026-06-12T00:00:00"
  },
  {
    slug: 'max-pain-strike-theory',
    title: 'How Max Pain Strike Affects Expiry Day Actions',
    summary: 'Explore the Max Pain theory and learn why the market spot price tends to gravitate towards the strike where option buyers lose the most capital.',
    category: 'Advanced Strategies',
    content: `## What Max Pain Is

Max Pain (or "option pain") is the strike price at which the *largest number of option buyers lose money* at expiry — equivalently, the strike where option **writers** (sellers) pay out the least. The theory holds that, as expiry approaches, the underlying tends to gravitate toward this strike.

It is a *tendency*, not a law. It works best on expiry day and in the absence of a strong trend or major news.

## Why the Pull Exists

Option writers are predominantly large, well-capitalised institutions. They collect premium up front and are motivated to see options expire worthless. Because they hedge dynamically (buying/selling the underlying to stay delta-neutral), their collective hedging activity can nudge price toward the level that minimises their total payout — the Max Pain strike.

## How Max Pain Is Calculated

For each candidate expiry strike S:
1. Compute the payout to **call** buyers if price closes at S: for every call strike below S, payout = (S - call strike) x that strike's call OI.
2. Compute the payout to **put** buyers if price closes at S: for every put strike above S, payout = (put strike - S) x that strike's put OI.
3. Add all call and put payouts → total payout if the index closes at S.
4. Repeat for every strike. The strike with the **minimum total payout** is the **Max Pain** strike.

## How to Trade Around Max Pain
- **Expiry-day magnet:** if spot is at 23,450 and Max Pain is 23,500, there is a statistical lean for spot to drift up toward 23,500 as writers defend their books.
- **Confluence beats Max Pain alone:** the signal is strongest when Max Pain lines up with the highest-OI support/resistance strikes and a neutral PCR.
- **Distance matters:** the farther spot is from Max Pain early in the week, the weaker the pull; it tightens as expiry nears.

## Important Limitations
- **Trends and news override it.** A strong directional move or a major event easily overpowers the Max Pain pull.
- **It shifts.** As OI builds and unwinds through the week, the Max Pain strike moves — recompute it, don't anchor to a stale value.
- **It is probabilistic.** Treat it as one weight of evidence, never a standalone trade trigger.

## Quick FAQ
**Q: Does the market always close at Max Pain?** No. It is a gravitational tendency near expiry, not a guarantee.

**Q: When is Max Pain most useful?** On expiry day, in range-bound conditions, when it aligns with OI support/resistance.`,
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
        scroll="paper"
        PaperProps={{
          sx: { bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 3, maxHeight: '90vh' }
        }}
      >
        {selectedArticle && (
          <>
            <DialogTitle sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, borderBottom: '1px solid', borderColor: 'divider', pb: 2 }}>
              {selectedArticle.title}
            </DialogTitle>
            <DialogContent dividers sx={{ overflowY: 'auto' }}>
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
