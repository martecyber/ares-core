CREATE TABLE ares.kb_third_party_entry (
    id         BIGSERIAL    PRIMARY KEY,
    kind       VARCHAR(30)  NOT NULL,
    value      VARCHAR(500) NOT NULL,
    category   VARCHAR(50)  NOT NULL DEFAULT 'other',
    notes      TEXT,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (kind, value)
);

-- Seed: social media
INSERT INTO ares.kb_third_party_entry (kind, value, category, notes) VALUES
('domain',          'twitter.com',       'social_media', 'Twitter / X'),
('domain_wildcard', '*.twitter.com',     'social_media', 'Twitter / X subdomains'),
('domain',          'x.com',             'social_media', 'Twitter / X (new domain)'),
('domain_wildcard', '*.x.com',           'social_media', 'Twitter / X subdomains (new domain)'),
('domain',          'facebook.com',      'social_media', 'Facebook'),
('domain_wildcard', '*.facebook.com',    'social_media', 'Facebook subdomains'),
('domain',          'instagram.com',     'social_media', 'Instagram'),
('domain_wildcard', '*.instagram.com',   'social_media', 'Instagram subdomains'),
('domain',          'linkedin.com',      'social_media', 'LinkedIn'),
('domain_wildcard', '*.linkedin.com',    'social_media', 'LinkedIn subdomains'),
('domain',          'youtube.com',       'social_media', 'YouTube'),
('domain_wildcard', '*.youtube.com',     'social_media', 'YouTube subdomains'),
('domain',          'tiktok.com',        'social_media', 'TikTok'),
('domain_wildcard', '*.tiktok.com',      'social_media', 'TikTok subdomains'),
('domain',          'reddit.com',        'social_media', 'Reddit'),
('domain_wildcard', '*.reddit.com',      'social_media', 'Reddit subdomains'),
('domain',          'pinterest.com',     'social_media', 'Pinterest'),
('domain',          'snapchat.com',      'social_media', 'Snapchat'),
('domain',          'whatsapp.com',      'social_media', 'WhatsApp'),
('domain',          'telegram.org',      'social_media', 'Telegram'),

-- Seed: CDN / static assets
('domain_wildcard', '*.googleapis.com',  'cdn', 'Google APIs / CDN'),
('domain_wildcard', '*.gstatic.com',     'cdn', 'Google Static CDN'),
('domain_wildcard', '*.cloudflare.com',  'cdn', 'Cloudflare CDN'),
('domain_wildcard', '*.akamai.net',      'cdn', 'Akamai CDN'),
('domain_wildcard', '*.akamaized.net',   'cdn', 'Akamai CDN (akamaized)'),
('domain_wildcard', '*.fastly.net',      'cdn', 'Fastly CDN'),
('domain_wildcard', '*.cloudfront.net',  'cdn', 'AWS CloudFront CDN'),
('domain_wildcard', '*.jsdelivr.net',    'cdn', 'jsDelivr CDN'),
('domain',          'unpkg.com',         'cdn', 'UNPKG CDN'),
('domain',          'cdnjs.cloudflare.com', 'cdn', 'Cloudflare CDNJS'),

-- Seed: analytics / tracking
('domain_wildcard', '*.google-analytics.com', 'analytics', 'Google Analytics'),
('domain_wildcard', '*.googletagmanager.com', 'analytics', 'Google Tag Manager'),
('domain_wildcard', '*.hotjar.com',      'analytics', 'Hotjar'),
('domain_wildcard', '*.mixpanel.com',    'analytics', 'Mixpanel'),
('domain_wildcard', '*.segment.com',     'analytics', 'Segment'),
('domain_wildcard', '*.amplitude.com',   'analytics', 'Amplitude'),
('domain_wildcard', '*.newrelic.com',    'analytics', 'New Relic'),

-- Seed: advertising
('domain_wildcard', '*.doubleclick.net',        'advertising', 'Google DoubleClick'),
('domain_wildcard', '*.googlesyndication.com',  'advertising', 'Google Syndication (AdSense)'),

-- Seed: payment processors
('domain_wildcard', '*.stripe.com',             'payment', 'Stripe'),
('domain_wildcard', '*.paypal.com',             'payment', 'PayPal'),
('domain_wildcard', '*.braintreegateway.com',   'payment', 'Braintree'),

-- Seed: cloud storage
('domain_wildcard', '*.s3.amazonaws.com',         'cloud', 'AWS S3'),
('domain_wildcard', '*.blob.core.windows.net',    'cloud', 'Azure Blob Storage'),
('domain_wildcard', '*.storage.googleapis.com',   'cloud', 'Google Cloud Storage');

