CREATE TABLE IF NOT EXISTS library_files (
  user_id TEXT NOT NULL,
  file_id TEXT NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('drive', 'local')),
  name TEXT NOT NULL,
  title TEXT,
  artist TEXT,
  album TEXT,
  duration_seconds REAL,
  format TEXT,
  size_bytes INTEGER,
  revision TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  checked_at TEXT NOT NULL,
  PRIMARY KEY (user_id, file_id)
);

CREATE INDEX IF NOT EXISTS library_files_user_artist
  ON library_files (user_id, artist);

CREATE TABLE IF NOT EXISTS playlists (
  user_id TEXT NOT NULL,
  id TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT 'manual',
  source_url TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (user_id, id)
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
  user_id TEXT NOT NULL,
  playlist_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  title TEXT NOT NULL,
  artist TEXT NOT NULL DEFAULT '',
  album TEXT NOT NULL DEFAULT '',
  duration_seconds REAL,
  isrc TEXT,
  source_url TEXT,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  PRIMARY KEY (user_id, playlist_id, position),
  FOREIGN KEY (user_id, playlist_id) REFERENCES playlists(user_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS api_rate_limits (
  user_id TEXT PRIMARY KEY NOT NULL,
  window_start INTEGER NOT NULL,
  request_count INTEGER NOT NULL DEFAULT 0
);
