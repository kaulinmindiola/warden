-- Index for the periodic discard of expired sealed link tokens (REQ-SEC-005, SDD V06). V18 was
-- merged empty, so the index it is named after was never created; a merged migration is never
-- modified (REQ-CM-003), so this migration creates it. Only deliveries that still hold a sealed
-- token are indexed.

CREATE INDEX notification_delivery_secret_expiry_idx ON notification_delivery (secret_expires_at)
    WHERE secret_ciphertext IS NOT NULL;