# Known Issues

## Current known bugs and limitations

### saveToFile not always called

**Issue:** When the user asks to save something to a file, the model sometimes stops after calling `summarize` without calling `saveToFile`.

**Workaround:** Ask the agent explicitly: "Now save that to a file called X.md". The system prompt instructs the model to call `saveToFile` after `summarize`, but enforcement is not guaranteed.

**Status:** Under investigation. May need stronger enforcement in the system prompt or a post-processing check.

### MCP client does not reconnect

**Issue:** If the MCP server is down when the agent starts, the MCP client does not reconnect later. Tools remain unavailable until the agent is restarted.

**Impact:** After a server restart, the MCP server must start before the agent. The systemd units enforce this ordering.

**Workaround:** If tools are unavailable, restart the agent: `sudo systemctl restart aiac-agent`

### RAG citations display bug

**Issue:** RAG source citations show numbered references [1], [2] but the actual file names are blank in the UI.

**Status:** Fix in progress. The underlying data is correct — only the display rendering is affected.

### Single timezone for briefings

**Issue:** The briefing scheduler uses one timezone (`GOOGLE_TIME_ZONE`) for all users. There is no per-user timezone detection or configuration.

**Impact:** Users in different timezones from the server will receive briefings at unexpected times.

**Workaround:** Set `GOOGLE_TIME_ZONE` to match the primary user's timezone.

### Context window pressure with RAG

**Issue:** Enabling RAG significantly increases token usage. Long conversations with RAG enabled can hit the 50,000 token limit quickly.

**Workaround:** Start new conversations for unrelated topics. Disable RAG when not needed.

### Conversation memory resets with cookies

**Issue:** Long-term memory is keyed by the `visitor-id` cookie. Clearing cookies or using incognito mode creates a new identity with no memory.

**Impact:** Users who clear their browser data lose their conversation history and memory.

**Note:** This is by design — there are no user accounts. However, users often expect persistence across browser sessions even after clearing data.
