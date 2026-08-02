CREATE TABLE sites (
    id TEXT NOT NULL,
    org_id TEXT NOT NULL,
    name TEXT NOT NULL,
    address TEXT,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    geofence_radius_m INTEGER,
    PRIMARY KEY (org_id, id)
);

CREATE TABLE assets (
    id TEXT PRIMARY KEY,
    org_id TEXT NOT NULL,
    site_id TEXT NOT NULL,
    name TEXT NOT NULL,
    inventory_no TEXT,
    category TEXT,
    description TEXT,
    status TEXT NOT NULL,
    source TEXT NOT NULL,
    document_ids JSONB NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX assets_org_id_idx ON assets (org_id);
CREATE INDEX assets_org_site_idx ON assets (org_id, site_id);

CREATE TABLE user_scopes (
    org_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    site_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (org_id, user_id)
);
