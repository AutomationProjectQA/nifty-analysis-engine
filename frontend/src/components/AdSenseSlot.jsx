import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { Box, Typography } from '@mui/material';

const AdSenseSlot = ({ adSlot, adFormat = 'auto', responsive = 'true', style = { display: 'block', width: '100%' } }) => {
  const [adBlocked, setAdBlocked] = useState(false);

  useEffect(() => {
    // Check if the script is loaded, else set blocked to show fallback.
    const timer = setTimeout(() => {
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
    return () => clearTimeout(timer); // avoid a state update after unmount
  }, []);

  const clientId = import.meta.env.VITE_ADSENSE_CLIENT_ID;

  // No ad available (client missing or blocked). In production render nothing — never show
  // end users a fake "advertisement". In dev, show a neutral, non-clickable placeholder.
  if (!clientId || adBlocked) {
    if (!import.meta.env.DEV) return null;
    return (
      <Box
        sx={{
          margin: '20px 0', padding: '12px', border: '1px dashed #e9eaf2',
          backgroundColor: '#f7f8fc', borderRadius: '8px', textAlign: 'center',
          color: 'text.disabled', minHeight: '60px',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}
      >
        <Typography variant="caption">Ad slot (dev): {adSlot}</Typography>
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
