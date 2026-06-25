import React, { useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Link, useLocation } from 'react-router-dom';
import { ThemeProvider, Box, CssBaseline, AppBar, Toolbar, Typography, Drawer, List, ListItem, ListItemButton, ListItemIcon, ListItemText, Divider, Chip, IconButton } from '@mui/material';
import DashboardIcon from '@mui/icons-material/Dashboard';
import BarChartIcon from '@mui/icons-material/BarChart';
import BoltIcon from '@mui/icons-material/Bolt';
import DescriptionIcon from '@mui/icons-material/Description';
import NewspaperIcon from '@mui/icons-material/Newspaper';
import CalculateIcon from '@mui/icons-material/Calculate';
import SchoolIcon from '@mui/icons-material/School';
import AssessmentIcon from '@mui/icons-material/Assessment';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import MenuIcon from '@mui/icons-material/Menu';

import theme from './theme';
import useBackendStatus from './hooks/useBackendStatus';
import { useStreamConnected } from './api/marketStream';
import ErrorBoundary from './components/ErrorBoundary';
import AdSenseSlot from './components/AdSenseSlot';
import Dashboard from './pages/Dashboard';
import OptionChain from './pages/OptionChain';
import AiSignals from './pages/AiSignals';
import Performance from './pages/Performance';
import MarketReports from './pages/MarketReports';
import Calculators from './pages/Calculators';
import LearningCenter from './pages/LearningCenter';
import NewsIntelligence from './pages/NewsIntelligence';

const drawerWidth = 240;

const menuItems = [
  { text: 'Dashboard', icon: <DashboardIcon />, path: '/' },
  { text: 'Option Chain', icon: <BarChartIcon />, path: '/option-chain' },
  { text: 'AI Signals', icon: <BoltIcon />, path: '/signals' },
  { text: 'Performance', icon: <AssessmentIcon />, path: '/performance' },
  { text: 'Market Reports', icon: <DescriptionIcon />, path: '/reports' },
  { text: 'Calculators', icon: <CalculateIcon />, path: '/calculators' },
  { text: 'Learning Center', icon: <SchoolIcon />, path: '/learning' },
  { text: 'News Intelligence', icon: <NewspaperIcon />, path: '/news' },
];

function NavigationLayout({ children }) {
  const location = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);
  const online = useBackendStatus();
  const streaming = useStreamConnected();

  const handleDrawerToggle = () => {
    setMobileOpen(!mobileOpen);
  };

  const drawerContent = (
    <Box sx={{ overflow: 'auto', display: 'flex', flexDirection: 'column', height: '100%', justifyContent: 'space-between' }}>
      <List sx={{ p: 2, gap: 0.5, display: 'flex', flexDirection: 'column' }}>
        {menuItems.map((item) => {
          const active = location.pathname === item.path;
          return (
            <ListItem key={item.text} disablePadding>
              <ListItemButton
                component={Link}
                to={item.path}
                onClick={() => setMobileOpen(false)}
                sx={{
                  borderRadius: 2,
                  bgcolor: active ? 'rgba(38, 166, 154, 0.1)' : 'transparent',
                  color: active ? 'primary.main' : 'text.secondary',
                  '&:hover': {
                    bgcolor: active ? 'rgba(38, 166, 154, 0.15)' : 'rgba(255, 255, 255, 0.03)',
                    color: active ? 'primary.main' : 'text.primary',
                  },
                }}
              >
                <ListItemIcon sx={{ color: active ? 'primary.main' : 'text.secondary', minWidth: 40 }}>
                  {item.icon}
                </ListItemIcon>
                <ListItemText primary={item.text} primaryTypographyProps={{ fontWeight: active ? 600 : 500, fontSize: '0.9rem' }} />
              </ListItemButton>
            </ListItem>
          );
        })}
      </List>
      
      {/* Sidebar Ad Placement */}
      <Box sx={{ p: 2 }}>
        <Divider sx={{ mb: 2 }} />
        <AdSenseSlot adSlot="sidebar-banner" adFormat="vertical" />
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <CssBaseline />
      
      {/* Top Navbar */}
      <AppBar position="fixed" sx={{ zIndex: (theme) => theme.zIndex.drawer + 1, bgcolor: '#131722', borderBottom: '1px solid #1e222d', boxShadow: 'none' }}>
        <Toolbar sx={{ justifyContent: 'space-between' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <IconButton
              color="inherit"
              aria-label="open drawer"
              edge="start"
              onClick={handleDrawerToggle}
              sx={{ mr: 2, display: { md: 'none' } }}
            >
              <MenuIcon />
            </IconButton>
            <Typography variant="h6" noWrap component="div" sx={{ fontFamily: 'Outfit, sans-serif', fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
              <Box component="span" sx={{ color: 'primary.main', display: 'flex', alignItems: 'center' }}>
                <BoltIcon sx={{ fontSize: 28 }} />
              </Box>
              Nifty Intelligence Portal
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            {(() => {
              const color = online === false ? '#ef5350' : '#26a69a';
              const label = online === null
                ? 'Connecting…'
                : online
                  ? (streaming ? 'Live • Streaming' : 'Live Connection')
                  : 'Offline — demo data';
              const rgb = online === false ? '239, 83, 80' : '38, 166, 154';
              return (
                <Chip
                  icon={<FiberManualRecordIcon sx={{ fontSize: '10px !important', color: `${color} !important` }} />}
                  label={label}
                  size="small"
                  sx={{ bgcolor: `rgba(${rgb}, 0.1)`, border: `1px solid rgba(${rgb}, 0.2)`, color, fontWeight: 600 }}
                />
              );
            })()}
          </Box>
        </Toolbar>
      </AppBar>

      {/* Sidebar Drawer for mobile (temporary) */}
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={handleDrawerToggle}
        ModalProps={{
          keepMounted: true, // Better open performance on mobile.
        }}
        sx={{
          display: { xs: 'block', md: 'none' },
          [`& .MuiDrawer-paper`]: { width: drawerWidth, boxSizing: 'border-box', bgcolor: '#131722', borderRight: '1px solid #1e222d' },
        }}
      >
        <Toolbar />
        {drawerContent}
      </Drawer>

      {/* Sidebar Drawer for desktop (permanent) */}
      <Drawer
        variant="permanent"
        sx={{
          display: { xs: 'none', md: 'block' },
          width: drawerWidth,
          flexShrink: 0,
          [`& .MuiDrawer-paper`]: { width: drawerWidth, boxSizing: 'border-box', bgcolor: '#131722', borderRight: '1px solid #1e222d' },
        }}
      >
        <Toolbar />
        {drawerContent}
      </Drawer>

      {/* Main Content Pane */}
      <Box component="main" sx={{ flexGrow: 1, p: 3, pt: 10, width: { md: `calc(100% - ${drawerWidth}px)` }, bgcolor: 'background.default' }}>
        <ErrorBoundary resetKey={location.pathname}>
          {children}
        </ErrorBoundary>
      </Box>
    </Box>
  );
}

function App() {
  return (
    <ThemeProvider theme={theme}>
      <Router>
        <NavigationLayout>
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/option-chain" element={<OptionChain />} />
            <Route path="/signals" element={<AiSignals />} />
            <Route path="/performance" element={<Performance />} />
            <Route path="/reports" element={<MarketReports />} />
            <Route path="/calculators" element={<Calculators />} />
            <Route path="/learning" element={<LearningCenter />} />
            <Route path="/news" element={<NewsIntelligence />} />
          </Routes>
        </NavigationLayout>
      </Router>
    </ThemeProvider>
  );
}

export default App;
