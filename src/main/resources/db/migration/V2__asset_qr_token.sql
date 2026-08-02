ALTER TABLE assets ADD COLUMN IF NOT EXISTS qr_token TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS assets_org_qr_token_uq
    ON assets (org_id, qr_token)
    WHERE qr_token IS NOT NULL;
