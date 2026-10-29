-- devices is a phone table: the ingester replaces it from each "home" fact (House.snapshot on the phone). Athena reads it.
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,          -- Google Home device id
  name TEXT,
  kind TEXT,                    -- Light, Plug, Switch, Fan, Thermostat, Speaker, TV, Sensor, Lock, Garage door, Security system, Camera, Doorbell, Device
  room TEXT,
  online INTEGER,
  on_off INTEGER,               -- 1 on, 0 off, NULL unknown
  level INTEGER,                -- brightness percent
  kelvin INTEGER,
  temp_c REAL,                  -- a sensor's or thermostat's reading
  controllable INTEGER,         -- 1 only for lights, plugs, switches and fans with on/off; never locks, garage, cameras, doorbells, security
  at INTEGER                    -- ms of the snapshot
);

-- Every change to his home, whoever made it. His taps on the page add a row; Athena adds one for each action she takes.
CREATE TABLE IF NOT EXISTS log (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,          -- ms
  device_id TEXT,
  device TEXT,                  -- the name, as it was
  action TEXT NOT NULL,         -- "on", "off", "brightness 40"
  before TEXT,                  -- JSON of what it was ({"on":1,"level":80}), so Undo can put it back
  who TEXT NOT NULL,            -- owner, athena
  why TEXT,
  undone_at INTEGER             -- ms, when he pressed Undo on it
);
CREATE INDEX IF NOT EXISTS log_at ON log(at);
