import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { Box, Typography } from '@mui/material';

const AdSenseSlot = ({ adSlot, adFormat = 'auto', responsive = 'true', style = { display: 'block', width: '100%' } }) => {
  const [adBlocked, setAdBlocked] = useState(false);

  useEffect(() => {
    // Check if the script is loaded, else set blocked to show fallback
    setTimeout(() => {
      try {
        if (window.adsbygoogle) {
          (window.adsbygoogle = window.adsbygoogle || []).push({});
        } else {
          setAdBlocked(true);
        }
      } catch (e) {
        console.warn('AdSense blocked or failed to load:', e);
        setAdBlocked(true);
      }
    }, 500);
  }, []);

  const clientId = import.meta.env.VITE_ADSENSE_CLIENT_ID;

  // Fallback state if AdSense client is missing or blocked
  if (!clientId || adBlocked) {
    return (
      <Box
        sx={{
          margin: '20px 0',
          padding: '20px',
          border: '1px dashed #1e222d',
          backgroundColor: '#171b26',
          borderRadius: '8px',
          textAlign: 'center',
          color: '#b2b5be',
          minHeight: '90px',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          cursor: 'pointer',
          '&:hover': {
            borderColor: '#26a69a',
            backgroundColor: '#1e222d',
          }
        }}
        onClick={() => window.open('https://google.com/adsense/start', '_blank')}
      >
        <Typography variant="caption" sx={{ fontWeight: 600, letterSpacing: '1px', textTransform: 'uppercase', color: '#ffb300' }}>
          Sponsored Advertisement
        </Typography>
        <Typography variant="body2" sx={{ fontSize: '0.75rem', mt: 0.5 }}>
          Monetize your Nifty analysis traffic. Click to set up your Google AdSense workspace. (Slot ID: {adSlot})
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ margin: '20px 0', width: '100%', overflow: 'hidden', display: 'flex', justifyContent: 'center' }}>
      <ins
        className="adsbygoogle"
        style={style}
        data-ad-client={clientId}
        data-ad-slot={adSlot}
        data-ad-format={adFormat}
        data-full-width-responsive={responsive}
      />
    </Box>
  );
};

AdSenseSlot.propTypes = {
  adSlot: PropTypes.string,
  adFormat: PropTypes.string,
  responsive: PropTypes.string,
  style: PropTypes.object,
};

export default AdSenseSlot;
