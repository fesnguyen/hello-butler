-- Adopt existing V1 content without deleting data or maintaining a migration history.
DO $$
BEGIN
  IF to_regclass('applications') IS NULL AND to_regclass('showcase_applications') IS NOT NULL THEN
    ALTER TABLE showcase_applications RENAME TO applications;
  END IF;
  IF to_regclass('media') IS NULL AND to_regclass('showcase_media') IS NOT NULL THEN
    ALTER TABLE showcase_media RENAME TO media;
  END IF;
  IF to_regclass('releases') IS NULL AND to_regclass('showcase_releases') IS NOT NULL THEN
    ALTER TABLE showcase_releases RENAME TO releases;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS applications (
  id uuid PRIMARY KEY,
  name varchar(200) NOT NULL,
  description text NOT NULL,
  github_url text
);
CREATE TABLE IF NOT EXISTS media (
  id uuid PRIMARY KEY,
  application_id uuid NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  url text NOT NULL,
  display_order integer NOT NULL
);
CREATE TABLE IF NOT EXISTS releases (
  id uuid PRIMARY KEY,
  application_id uuid NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
  version varchar(100) NOT NULL,
  download_url text NOT NULL,
  published_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS media_application_order ON media(application_id, display_order, id);
CREATE INDEX IF NOT EXISTS releases_application_date ON releases(application_id, published_at DESC, id DESC);
