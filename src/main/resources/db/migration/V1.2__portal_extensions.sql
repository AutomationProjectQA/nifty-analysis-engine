-- Alter trade_signal to support thesis text
ALTER TABLE trade_signal ADD COLUMN thesis TEXT;

-- Create ai_report table
CREATE TABLE ai_report (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    publish_date DATE NOT NULL,
    report_text TEXT NOT NULL,
    generated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_ai_report_type_date ON ai_report(type, publish_date);

-- Create market_news table
CREATE TABLE market_news (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    source_url VARCHAR(512),
    importance VARCHAR(20) NOT NULL,
    published_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_market_news_published ON market_news(published_at DESC);

-- Create learning_article table
CREATE TABLE learning_article (
    id BIGSERIAL PRIMARY KEY,
    slug VARCHAR(100) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) NOT NULL,
    published_at TIMESTAMP NOT NULL
);

-- Seed default learning center articles
INSERT INTO learning_article (slug, title, summary, content, category, published_at) VALUES 
(
  'understanding-open-interest',
  'What is Open Interest (OI) & How to Use It?',
  'Learn the fundamentals of Open Interest (OI) and how options writers build positions to create key market supports and resistance barriers.',
  'Open Interest (OI) represents the total number of outstanding derivative contracts, such as options or futures, that have not been settled. In option trading, understanding OI is crucial because it indicates where market participants are committing capital.\n\n### Call vs Put Open Interest\n- **Call Open Interest (CE OI):** High concentration at a particular strike indicates heavy call writing. Option sellers expect the market to remain below this level, creating a strong **resistance barrier**.\n- **Put Open Interest (PE OI):** High concentration at a strike indicates heavy put writing. Option sellers expect the market to stay above this level, creating a strong **support barrier**.\n\n### Reading OI Build-ups\nBy looking at price changes alongside changes in Open Interest, traders can detect four core market biases:\n1. **Long Build-up:** Price rises, OI rises. Indicates new buyers entering the market with strong bullish momentum.\n2. **Short Build-up:** Price falls, OI rises. Indicates sellers adding aggressive short positions.\n3. **Long Unwinding:** Price falls, OI falls. Indicates buyers exiting their positions, leading to weakening momentum.\n4. **Short Covering:** Price rises, OI falls. Indicates short sellers closing positions, which often leads to a fast short squeeze rally.',
  'Options Basics',
  NOW()
),
(
  'put-call-ratio-pcr-guide',
  'Put-Call Ratio (PCR) Explained for Beginners',
  'A complete guide to interpreting Put-Call Ratio (PCR) to gauge overall market sentiment and identify potential market reversals.',
  'The Put-Call Ratio (PCR) is a popular technical sentiment indicator used by traders to measure overall market positioning. It is calculated by dividing the total volume or open interest of Put options by the total volume or open interest of Call options.\n\n### How to Calculate PCR\n$$\\text{PCR (OI)} = \\frac{\\text{Total PE Open Interest}}{\\text{Total CE Open Interest}}$$\n\n### How to Interpret PCR Values\n- **PCR > 1.10 (Bullish Sentiment):** High PCR values indicate that traders are writing more Puts than Calls. This serves as a bullish indicator, suggesting that market participants expect price support levels to hold.\n- **PCR < 0.70 (Bearish Sentiment):** Low PCR values indicate that Call writing dominates over Put writing. This is a bearish indicator, indicating resistance from sellers.\n- **PCR between 0.70 and 1.10 (Neutral/Sideways):** Indicates balanced options writing activity with no strong directional bias. Expect sideways price action.\n\n### Extreme PCR Reversals (Overbought/Oversold)\nExtremely high PCR levels (e.g. > 1.50) can sometimes indicate that the market is oversold and a short-term bounce is likely, as put writers have saturated the market. Conversely, extremely low PCR levels (e.g. < 0.50) can signal that the market is overbought and due for a correction.',
  'Technical Analysis',
  NOW()
),
(
  'max-pain-strike-theory',
  'How Max Pain Strike Affects Expiry Day Actions',
  'Explore the Max Pain theory and learn why the market spot price tends to gravitate towards the strike where option buyers lose the most capital.',
  'Max Pain, also known as option pain, is a theory that states that on option expiry day, the price of the underlying asset will gravitate towards the strike price where the maximum number of option buyers will lose money (i.e. options expire worthless).\n\n### The Logic Behind Max Pain\nOption writers (sellers), who are typically large institutional traders (FIIs/DIIs/Prop desks), write options to collect premiums. Because they write options with substantial capital, they have a vested interest in hedging their positions to ensure the index closes at a strike that minimizes their total payout to buyers.\n\n### Calculating Max Pain\nTo find the Max Pain strike:\n1. For each strike price, calculate the cumulative cash payout to option buyers if the spot price closes exactly at that strike.\n2. Add the total payouts for both Calls and Puts at each level.\n3. The strike price with the **minimum total payout** is identified as the **Max Pain Strike**.\n\n### Trading with Max Pain\nTraders watch the Max Pain strike as a strong magnet on expiry days. If Nifty spot is trading at 23,450 but the options chain shows Max Pain is at 23,500, there is a statistical probability that spot will drift up towards 23,500 as large institutions adjust and defend their options writing positions.',
  'Advanced Strategies',
  NOW()
);
