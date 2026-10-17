# Telegram Briefings

## How Telegram delivery works

AIAC can send daily briefings to Telegram. The MCP server uses a Telegram bot to deliver messages. Configuration requires two environment variables:
- `TELEGRAM_BOT_TOKEN` — the bot token from BotFather
- `TELEGRAM_CHAT_ID` — the chat ID where messages should be sent

If either is missing or blank, the Telegram notifier becomes a no-op — briefings are still collected and stored, but not delivered to Telegram.

## Common issues

### Briefing not arriving on Telegram

**Symptom:** Briefings show on the web UI but nothing arrives on Telegram.

**Possible causes:**

1. **Telegram not linked in user profile:** Check if the user's `telegram.linked` is `true` and `chatId` is set. If `linked: false` or `chatId: null`, the user has not completed Telegram setup.

2. **Bot token not configured:** If `TELEGRAM_BOT_TOKEN` is empty on the server, no Telegram messages can be sent. Check the MCP server's environment.

3. **Wrong chat ID:** The `chatId` must match the actual Telegram chat. If the user started a new chat with the bot, the old chat ID is stale.

4. **Bot blocked by user:** If the user blocked the bot on Telegram, messages silently fail. The user must unblock the bot and send `/start` again.

5. **Schedule not active:** The briefing schedule must be set to something other than "off". Check the briefing panel in the web UI.

### How to verify Telegram setup

1. Confirm `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` are set in `/opt/aiac/.env`
2. Check user profile: `telegram.linked` should be `true` with a valid `chatId`
3. Check briefing schedule is not "off"
4. Check MCP server logs for delivery errors: `sudo journalctl -u aiac-mcp`

### Briefing collected but not narrated

**Symptom:** The briefing panel shows figures (events, tasks) but the narrative is empty.

**Cause:** The `CEREBRAS_API_KEY` is missing or the Cerebras API call failed. The briefing collector still gathers data even when narration fails.

**Resolution:** Verify `CEREBRAS_API_KEY` is set and valid. Check MCP server logs for API errors.
