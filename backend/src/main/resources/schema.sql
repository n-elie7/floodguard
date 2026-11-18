CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS sectors (
 id text PRIMARY KEY, name text NOT NULL, district text NOT NULL,
 boundary geometry(MultiPolygon,4326) NOT NULL,
 latitude double precision NOT NULL, longitude double precision NOT NULL,
 moderate_threshold int NOT NULL DEFAULT 40, high_threshold int NOT NULL DEFAULT 70,
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT valid_thresholds CHECK (moderate_threshold BETWEEN 1 AND 98 AND high_threshold BETWEEN moderate_threshold + 1 AND 100)
);
CREATE INDEX IF NOT EXISTS sectors_boundary_gix ON sectors USING gist(boundary);

CREATE TABLE IF NOT EXISTS roads (
 id text PRIMARY KEY, name text NOT NULL, road_type text,
 geom geometry(MultiLineString,4326) NOT NULL,
 vulnerability double precision,
 vulnerability_source text,
 CONSTRAINT valid_vulnerability CHECK (vulnerability IS NULL OR vulnerability BETWEEN 0 AND 1)
);
CREATE INDEX IF NOT EXISTS roads_geom_gix ON roads USING gist(geom);

CREATE TABLE IF NOT EXISTS shelters (
 id bigserial PRIMARY KEY, sector_id text REFERENCES sectors(id) ON DELETE CASCADE,
 name text NOT NULL, location geometry(Point,4326) NOT NULL,
 verified boolean NOT NULL DEFAULT false, source text
);

CREATE TABLE IF NOT EXISTS weather_snapshots (
 id bigserial PRIMARY KEY, sector_id text NOT NULL REFERENCES sectors(id) ON DELETE CASCADE,
 observed_at timestamptz NOT NULL DEFAULT now(),
 forecast_rainfall_mm double precision NOT NULL,
 soil_moisture double precision NOT NULL,
 baseline_daily_mm double precision NOT NULL,
 precipitation_probability double precision,
 hourly_forecast jsonb NOT NULL,
 source text NOT NULL DEFAULT 'Open-Meteo'
);

CREATE TABLE IF NOT EXISTS risk_assessments (
 id bigserial PRIMARY KEY, sector_id text NOT NULL REFERENCES sectors(id) ON DELETE CASCADE,
 snapshot_id bigint NOT NULL REFERENCES weather_snapshots(id),
 assessed_at timestamptz NOT NULL DEFAULT now(), score int NOT NULL,
 level text NOT NULL, inputs jsonb NOT NULL,
 CONSTRAINT valid_score CHECK (score BETWEEN 0 AND 100)
);
CREATE INDEX IF NOT EXISTS risk_sector_time_idx ON risk_assessments(sector_id, assessed_at DESC);

CREATE TABLE IF NOT EXISTS road_impacts (
 id bigserial PRIMARY KEY, assessment_id bigint NOT NULL REFERENCES risk_assessments(id) ON DELETE CASCADE,
 road_id text NOT NULL REFERENCES roads(id),
 window_start timestamptz, window_end timestamptz,
 UNIQUE(assessment_id, road_id)
);

CREATE TABLE IF NOT EXISTS subscriptions (
 id bigserial PRIMARY KEY, sector_id text NOT NULL REFERENCES sectors(id) ON DELETE CASCADE,
 phone_hash text NOT NULL, phone_ciphertext text NOT NULL,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(sector_id, phone_hash)
);

CREATE TABLE IF NOT EXISTS alert_events (
 id bigserial PRIMARY KEY, sector_id text NOT NULL REFERENCES sectors(id) ON DELETE CASCADE,
 assessment_id bigint NOT NULL REFERENCES risk_assessments(id),
 level text NOT NULL, message text NOT NULL,
 triggered_at timestamptz NOT NULL DEFAULT now(),
 sent_count int NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS alert_time_idx ON alert_events(triggered_at DESC);

CREATE TABLE IF NOT EXISTS delivery_records (
 id bigserial PRIMARY KEY, alert_id bigint REFERENCES alert_events(id),
 subscription_id bigint REFERENCES subscriptions(id),
 provider_message_id text UNIQUE,
 status text NOT NULL, sent_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), error text
);

CREATE TABLE IF NOT EXISTS officer_accounts (
 username text PRIMARY KEY, password_hash text NOT NULL,
 must_change_password boolean NOT NULL DEFAULT true
);
