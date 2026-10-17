# Google Calendar Troubleshooting

## How Google Calendar integration works

AIAC connects to Google Calendar through OAuth 2.0. The MCP server (`port 8081`) holds a single set of OAuth credentials (`credentials.json`) and a cached token in `.google-tokens/StoredCredential`. The agent calls the `getSchedule(date)` tool, which queries the Google Calendar API.

## Common errors

### `invalid_grant` error

**Symptom:** The agent says "Nothing scheduled" even though there are meetings, or the MCP server logs show `invalid_grant`.

**Cause:** The OAuth refresh token has been invalidated. This happens when:
- The user changed their Google password
- The user revoked access in Google Account → Security → Third-party apps
- The token expired after 6 months of inactivity
- Google revoked the token for security reasons

**Resolution:**
1. Delete the `.google-tokens/` directory on the server
2. Restart the MCP server — it will open a browser for re-consent
3. Sign in with the correct Google account and grant calendar access
4. Restart the agent (it needs to reconnect to the MCP server)

**Important:** The MCP server must be restarted before the agent. The agent's MCP client does not reconnect if the server was down at boot.

### `access_revoked` error

**Symptom:** Same as `invalid_grant` — calendar queries return empty or fail.

**Cause:** The user explicitly removed AIAC from their Google Account → Security → Third-party apps.

**Resolution:** Same as `invalid_grant` — delete tokens and re-consent.

### `ACCESS_TOKEN_SCOPE_INSUFFICIENT`

**Symptom:** Calendar or Tasks API calls fail with 403.

**Cause:** The stored token was granted with narrower scopes than currently required. This happens when scopes are widened in the code but the old token is still cached.

**Resolution:** Delete `.google-tokens/` and re-consent. The new consent will include the updated scopes.

### Calendar shows wrong timezone

**Symptom:** Events appear on the wrong day or at the wrong time.

**Cause:** The `GOOGLE_TIME_ZONE` environment variable is not set or is set incorrectly. The MCP server uses this to determine day boundaries.

**Resolution:** Set `GOOGLE_TIME_ZONE` in the systemd unit (e.g., `Asia/Shanghai`). Restart the MCP server.

## Re-consent flow

1. SSH into the server
2. Stop the MCP server: `sudo systemctl stop aiac-mcp`
3. Delete tokens: `rm -rf /opt/aiac/.google-tokens/`
4. Start the MCP server manually to trigger consent: `cd /opt/aiac/mcp && java -jar mcp-server.jar`
5. Complete OAuth consent in the browser
6. Stop the manual server (Ctrl+C)
7. Start via systemd: `sudo systemctl start aiac-mcp`
8. Restart the agent: `sudo systemctl restart aiac-agent`
