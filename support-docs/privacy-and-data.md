# Privacy and Data

## What data AIAC stores

### On the server (`/opt/aiac/`)

| Data | Location | Retention |
|------|----------|-----------|
| Conversations | `agent/data/conversations.json` | Until manually deleted or conversation reset |
| Long-term memory | `agent/data/memory.json` | Persists across sessions, max 24 entries per visitor |
| Working memory (facts) | In-memory only | Resets with conversation |
| Invariants | `agent/data/invariants.json` | Permanent, human-written only |
| Profiles | `agent/profiles/` | Per-user personalization files |
| Briefings | `mcp/data/briefings.json` | Rolling history |
| Notes | `/opt/aiac/notes/` | Until manually deleted |
| Google OAuth tokens | `.google-tokens/StoredCredential` | Until deleted |

### What is sent to external services

- **Cerebras API:** Conversation messages, system prompts, tool results. Cerebras processes these to generate responses. Subject to Cerebras privacy policy.
- **Google APIs:** OAuth tokens for Calendar and Tasks read access. Only event metadata is retrieved — no event content is modified.
- **Open-Meteo:** City name for weather queries. No authentication required.
- **Frankfurter:** Currency conversion requests. No authentication required.
- **OpenAI:** Text chunks for embedding (when RAG embedding is enabled). Subject to OpenAI privacy policy.

### What is NOT stored

- Passwords or authentication credentials (Google OAuth uses token-based auth)
- Browser history or tracking data beyond the visitor cookie
- IP addresses (not logged by default)

## How to delete data

### Delete conversation history
Click "New Conversation" in the chat UI, or delete the visitor's entry from `conversations.json` on the server.

### Delete long-term memory
Clear from the memory panel in the chat UI, or remove the entry from `memory.json`.

### Revoke Google access
1. Go to Google Account → Security → Third-party apps
2. Remove AIAC
3. Delete `.google-tokens/` on the server

### Delete all data for a visitor
Remove their entries from `conversations.json`, `memory.json`, and their profile file from `profiles/`.

## Cookie usage

AIAC uses a single cookie (`visitor-id`) to identify returning visitors. This is a random UUID, not tied to any personal information. Clearing cookies creates a new visitor identity.
