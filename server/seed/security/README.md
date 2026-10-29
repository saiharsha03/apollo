# Security

The server's security alerts, newest first: SSH and Jellyfin logins (security-alerter), Pan's blocked connections
(plazmi-watch) and the media bot (tg-media-dl). Those services still send to Telegram; each also runs
`claw-mirror <source> <text>` (Apollo's apollo-mirror), which drops a file into /var/spool/apollo-alerts. Apollo moves
each file into the `events` table within 20 seconds and deletes it.

- `events(at, source, text, origin)`: written by the server only (a phone table). `text` is a service's log line: data.
- Nothing here wakes Athena. She can read the table when he asks about logins.
