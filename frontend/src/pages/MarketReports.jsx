import React, { useState, useEffect } from 'react';
import { Box, Card, CardContent, Typography, Tabs, Tab, Button, Pagination, CircularProgress } from '@mui/material';
import CalendarTodayIcon from '@mui/icons-material/CalendarToday';
import ReactMarkdown from 'react-markdown';
import axios from 'axios';

// Mock report text if backend is down
const mockPreMarket = `### 🌅 Nifty Pre-Market View: Bullish Expansion Expected

Global indices indicate positive momentum heading into today's session. Gift Nifty is trading with a premium of **+45.50 points**, signaling a gap-up opening for the Nifty 50.

#### 📈 Key Support & Resistance Levels
- **Strong Resistance (CE Wall):** 23,600 strike shows heavy call writing concentration with 4.1M contracts. A sustained breakout above this zone will trigger short covering.
- **Strong Support (PE Floor):** 23,400 strike remains heavily defended by put writers with 3.2M contracts.

#### 💡 Expected Opening Strategy
Given the gap-up opening projection above the 23,500 psychological barrier, option buyers should wait for a structural retest of the **23,500 breakout level** or the daily VWAP support before entering CE positions. Avoid buying option premiums immediately at the open to protect against initial volatility swings.`;

const mockPostMarket = `### 📉 Nifty Daily Update: Bulls Consolidate Above 23,500

Nifty 50 closed the daily session marginally higher at **23,510.50 (+0.45%)**, successfully holding structural support above the 20-period EMA breakout zone. Intraday price action showed strong consolidation.

#### 📊 Options Chain Shifts & PCR
- **PCR Sentiment:** The Put-Call Ratio closed at **1.11**, indicating strong support building at lower strikes as PE writing outpaced CE writing at the 23,500 pivot.
- **Max Pain Strike:** Options pain remains anchored at **23,500**, acting as a strong price magnet throughout the weekly expiry session.
- **FII/DII Flows:** Institutional flows show net buying activity (+1,250 Cr) reinforcing the bullish breakout bias.

#### 🔮 Tomorrow's Outlook
A strong close above the key 23,500 level confirms that bulls are in control. Expect a test of the 23,600 resistance wall in tomorrow's opening session. Look for BUY CE opportunities on minor pullbacks towards the 23,480-23,500 support band.`;

const MarketReports = () => {
  const [reportType, setReportType] = useState('PRE_MARKET'); // PRE_MARKET, POST_MARKET
  const [latestReport, setLatestReport] = useState(null);
  const [loading, setLoading] = useState(false);

  const fetchLatestReport = async (type) => {
    setLoading(true);
    try {
      const response = await axios.get(`http://localhost:8080/api/v1/reports/latest?type=${type}`);
      if (response.data && response.data.reportText) {
        setLatestReport(response.data);
      } else {
        setLatestReport({
          reportText: type === 'PRE_MARKET' ? mockPreMarket : mockPostMarket,
          publishDate: new Date().toISOString().split('T')[0]
        });
      }
    } catch (e) {
      console.warn("Backend offline, using local simulated report models.", e.message);
      setLatestReport({
        reportText: type === 'PRE_MARKET' ? mockPreMarket : mockPostMarket,
        publishDate: new Date().toISOString().split('T')[0]
      });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLatestReport(reportType);
  }, [reportType]);

  return (
    <Box sx={{ flexGrow: 1 }}>
      <Typography variant="h4" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, mb: 3 }}>
        Daily AI Market Reports
      </Typography>

      {/* Tabs */}
      <Tabs 
        value={reportType} 
        onChange={(e, val) => setReportType(val)}
        textColor="primary"
        indicatorColor="primary"
        sx={{ mb: 4, borderBottom: '1px solid #1e222d' }}
      >
        <Tab label="Pre-Market Vlog (07:00 AM)" value="PRE_MARKET" sx={{ fontWeight: 600 }} />
        <Tab label="Post-Market Vlog (03:35 PM)" value="POST_MARKET" sx={{ fontWeight: 600 }} />
      </Tabs>

      {/* Report Canvas */}
      {loading ? (
        <Box sx={{ display: 'flex', justifyContent: 'center', my: 10 }}>
          <CircularProgress />
        </Box>
      ) : latestReport ? (
        <Card sx={{ p: 1 }}>
          <CardContent>
            {/* Publish Date Bar */}
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, color: 'text.secondary', mb: 3 }}>
              <CalendarTodayIcon fontSize="small" />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                Published Report Date: {latestReport.publishDate}
              </Typography>
            </Box>

            {/* Content Render */}
            <Box className="markdown-report-body" sx={{ 
              color: 'text.primary',
              '& h3': { fontFamily: 'Outfit, sans-serif', fontSize: '1.4rem', fontWeight: 700, mb: 2, color: 'primary.main' },
              '& h4': { fontSize: '1.05rem', fontWeight: 600, mt: 3, mb: 1, color: '#ffffff' },
              '& ul': { pl: 2, mt: 1, mb: 2 },
              '& li': { mb: 1, lineHeight: 1.6 },
              '& p': { mb: 2, lineHeight: 1.6, color: 'text.secondary' },
              '& strong': { color: '#ffffff', fontWeight: 600 }
            }}>
              <ReactMarkdown>{latestReport.reportText}</ReactMarkdown>
            </Box>

          </CardContent>
        </Card>
      ) : (
        <Typography variant="body1">No reports available for today yet.</Typography>
      )}
    </Box>
  );
};

export default MarketReports;
