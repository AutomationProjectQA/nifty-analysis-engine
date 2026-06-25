import { createTheme } from '@mui/material/styles';

// Sensibull-inspired: bright, friendly, options-focused.
// Violet primary, clear bullish-green / bearish-red, soft cards on a light canvas.
const theme = createTheme({
  palette: {
    mode: 'light',
    background: {
      default: '#f4f5fb', // soft lavender-grey canvas
      paper: '#ffffff',
    },
    primary: {
      main: '#00b386',    // Sensibull green — brand + bullish
      light: '#e2f6f0',
      dark: '#00936e',
      contrastText: '#ffffff',
    },
    secondary: {
      main: '#e0483d',    // Bearish red
    },
    success: {
      main: '#00b386',    // Bullish green
    },
    error: {
      main: '#e0483d',
    },
    warning: {
      main: '#ff9f0a',    // Amber
    },
    info: {
      main: '#3d8bf5',
    },
    text: {
      primary: '#1b1d28',
      secondary: '#6b7185',
    },
    divider: '#e9eaf2',
    action: {
      hover: '#f4f5fb',
    },
  },
  shape: {
    borderRadius: 14,
  },
  typography: {
    fontFamily: 'Inter, Outfit, sans-serif',
    h1: { fontSize: '2rem', fontWeight: 700, letterSpacing: '-0.02em' },
    h2: { fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.01em' },
    h4: { fontSize: '1.6rem', fontWeight: 700, letterSpacing: '-0.01em' },
    h5: { fontSize: '1.25rem', fontWeight: 700 },
    h6: { fontSize: '1.05rem', fontWeight: 700 },
    body1: { fontSize: '0.95rem', lineHeight: 1.6 },
    body2: { fontSize: '0.85rem', color: '#6b7185' },
    button: { fontWeight: 600 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: '#f4f5fb' },
      },
    },
    MuiCard: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          backgroundColor: '#ffffff',
          border: '1px solid #e9eaf2',
          borderRadius: 16,
          boxShadow: '0 1px 2px rgba(16, 24, 40, 0.04), 0 1px 3px rgba(16, 24, 40, 0.04)',
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          borderRadius: 10,
          textTransform: 'none',
          fontWeight: 600,
          paddingInline: 16,
        },
        containedPrimary: {
          boxShadow: '0 1px 2px rgba(90, 61, 245, 0.25)',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 600, borderRadius: 8 },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none' },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottom: '1px solid #eef0f6',
          padding: '11px 16px',
        },
        head: {
          backgroundColor: '#f7f8fc',
          fontWeight: 700,
          color: '#6b7185',
          fontSize: '0.78rem',
          textTransform: 'uppercase',
          letterSpacing: '0.03em',
        },
      },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: { backgroundColor: '#1b1d28', fontSize: '0.78rem', borderRadius: 8 },
      },
    },
  },
});

export default theme;
