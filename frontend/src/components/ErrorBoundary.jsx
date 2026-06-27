import React from 'react';
import PropTypes from 'prop-types';
import { Box, Typography, Button } from '@mui/material';

/**
 * Catches render-time errors in a page so one crashing page doesn't blank the
 * whole app. Resets automatically when the route (resetKey) changes.
 */
class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, info) {
    console.error('Page render error:', error, info);
  }

  componentDidUpdate(prevProps) {
    if (prevProps.resetKey !== this.props.resetKey && this.state.hasError) {
      this.setState({ hasError: false, error: null });
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <Box sx={{ p: 4 }}>
          <Typography variant="h5" sx={{ fontWeight: 700, mb: 1 }}>
            Something went wrong on this page.
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 3 }}>
            {/* Show the raw error only in dev; users get a friendly message in prod. */}
            {import.meta.env.DEV
              ? (this.state.error?.message || 'An unexpected error occurred.')
              : 'Please try again, or refresh the page if it persists.'}
          </Typography>
          <Button variant="contained" onClick={() => this.setState({ hasError: false, error: null })}>
            Try again
          </Button>
        </Box>
      );
    }
    return this.props.children;
  }
}

ErrorBoundary.propTypes = {
  children: PropTypes.node,
  resetKey: PropTypes.any,
};

export default ErrorBoundary;
