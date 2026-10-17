# Plans and Feature Limits

## Available plans

### Free

- Chat with AI agent (basic)
- Google Calendar integration (read-only)
- Weather queries
- Currency conversion
- **20 messages per day**
- **No briefing scheduling**
- **No Telegram delivery**
- **No RAG (codebase search)**
- **No long-term memory** (working memory only, resets each session)

### Pro

- Everything in Free, plus:
- **Unlimited messages**
- **Briefing scheduling** (all intervals)
- **Telegram delivery**
- **RAG enabled** (codebase search)
- **Long-term memory** (24 entries, persists across sessions)
- **Notes — summarize and save to file**
- **Profile customization**

### Enterprise

- Everything in Pro, plus:
- **Priority support**
- **Custom briefing templates**
- **Extended context window** (when available)
- **API access** (planned)
- **Multi-user management** (planned)

## Common plan-related issues

### Free plan user cannot set briefing schedule

**Symptom:** The schedule button doesn't respond or the schedule dropdown is not visible.

**Cause:** Briefing scheduling is a Pro/Enterprise feature. Free plan users can see the briefing panel (showing the latest briefing if one was collected) but cannot configure automatic collection.

**Resolution:** Inform the user that this is a Pro feature. They can upgrade their plan to access scheduling.

### Free plan user hitting message limit

**Symptom:** After several messages, the user gets an error or the chat stops responding.

**Cause:** Free plan is limited to 20 messages per day. The counter resets at midnight UTC.

**Resolution:** The user can wait until the next day or upgrade to Pro for unlimited messages.

### Feature appears available but doesn't work

Some features are visible in the UI for all plans but only functional for Pro/Enterprise. This is by design — it lets users see what's available before upgrading. If a free user tries to use a gated feature, they should see a message explaining the limitation.
