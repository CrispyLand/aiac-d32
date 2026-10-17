# Briefing Schedule and Timezones

## How the briefing schedule works

The briefing scheduler runs inside the MCP server. Available schedule options:
- **off** — no automatic collection
- **every-minute** — for testing
- **every-5-minutes** — for testing
- **every-15-minutes**
- **hourly**
- **every-6-hours**
- **daily** — fires at the configured `daily-at` time (default: 07:00)

The schedule is persisted in `briefings.json` so it survives server restarts. Changing the schedule in the web UI takes effect immediately.

## Timezone handling

The MCP server uses `GOOGLE_TIME_ZONE` (set in the systemd unit) to determine:
- When the "daily" schedule fires
- What constitutes "today" when querying calendar events

**Important:** The timezone affects when events are grouped into days. The `CalendarReader` queries a wide UTC window (day ± 1) and filters by the event's stored date string. This avoids timezone-shifted boundary bugs.

## Common issues

### Briefing arrives at the wrong time

**Symptom:** Briefing is set for 8:00 AM but arrives at a different time (e.g., 4:00 PM or midnight).

**Cause:** The server's `GOOGLE_TIME_ZONE` does not match the user's actual timezone. The briefing fires at `daily-at` in the server's configured timezone, not the user's local timezone.

**Example:** If `GOOGLE_TIME_ZONE=UTC` and the user is in Shanghai (UTC+8), an 8:00 AM UTC briefing arrives at 4:00 PM Shanghai time.

**Resolution:**
1. Check `GOOGLE_TIME_ZONE` in the MCP server's systemd unit
2. Set it to match the primary user's timezone (e.g., `Asia/Shanghai`)
3. Restart the MCP server: `sudo systemctl restart aiac-mcp`

**Note:** AIAC currently supports one timezone per server instance. It does not auto-detect the user's timezone from the browser. Multi-timezone support is not yet implemented.

### Schedule button doesn't respond

**Symptom:** Clicking the schedule dropdown or save button has no effect.

**Possible causes:**
1. JavaScript error in the browser — check the browser console (F12)
2. The MCP server is not running — the schedule API call fails silently
3. Free plan limitation — check if the user's plan supports briefings (see plans.md)
