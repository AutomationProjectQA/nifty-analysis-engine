import { createTheme } from '@mui/material/styles';

const theme = createTheme({
  palette: {
    mode: 'dark',
    background: {
      default: '#0d0e12', // Deep slate graphite canvas
      paper: '#131722',   // Card panel backgrounds
    },
    primary: {
      main: '#26a69a',    // Vibrant Teal Green (bullish accent)
    },
    secondary: {
      main: '#ef5350',    // Crimson Red (bearish accent)
    },
    warning: {
      main: '#ffb300',    // Gold accent
    },
    text: {
      primary: '#ffffff',
      secondary: '#b2b5be',
    },
    divider: '#1e222d',   // Subtly dark borders
  },
  typography: {
    fontFamily: 'Inter, Outfit, sans-serif',
    h1: {
      fontSize: '2rem',
      fontWeight: 700,
    },
    h2: {
      fontSize: '1.5rem',
      fontWeight: 600,
    },
    h6: {
      fontSize: '1.1rem',
      fontWeight: 600,
    },
    body1: {
      fontSize: '0.95rem',
      lineHeight: 1.6,
    },
    body2: {
      fontSize: '0.85rem',
      color: '#b2b5be',
    },
  },
  components: {
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          backgroundColor: '#131722',
          border: '1px solid #1e222d',
          borderRadius: 12,
          boxShadow: '0 4px 12px rgba(0, 0, 0, 0.4)',
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          textTransform: 'none',
          fontWeight: 600,
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottom: '1px solid #1e222d',
          padding: '12px 16px',
        },
        head: {
          backgroundColor: '#171b26',
          fontWeight: 600,
          color: '#b2b5be',
        },
      },
    },
  },
});

export default theme;
