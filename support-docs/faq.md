# AIAC — Frequently Asked Questions

## What is AIAC?

AIAC is an AI-powered personal assistant that connects to your Google Calendar, Google Tasks, and other services through MCP (Model Context Protocol) tools. It provides a chat interface where you can ask about your schedule, get weather updates, convert currencies, and receive daily briefings.

## How does the chat work?

The chat UI at the root URL (`/`) connects to a Cerebras-hosted LLM (`gpt-oss-120b`). The agent has access to MCP tools — calendar, tasks, weather, currency, summarize, and save-to-file. When you ask a question, the agent decides which tools to call and composes an answer from the results.

## What MCP tools are available?

- **getSchedule(date)** — Google Calendar events for a given day
- **getTasks(date?)** — Google Tasks due on or before a date
- **getWeather(city?)** — Current weather via Open-Meteo (default city: Shanghai)
- **summarize(text, instructions?)** — AI text processing (read-only, returns result)
- **saveToFile(content, filename)** — Writes to the server's notes directory
- **get_rates / convert / list_currencies / list_providers** — Currency via Frankfurter

## What models does AIAC use?

AIAC uses **gpt-oss-120b** via the Cerebras API for all LLM tasks — chat, briefing narration, notes summarization, and code review. There is no model selector; the model is fixed.

## Can I use my own API key?

No. The Cerebras API key is configured on the server. Individual users do not need their own keys.

## Is my data stored?

- **Conversations** are stored in `conversations.json` on the server, keyed by a visitor cookie.
- **Long-term memory** is stored per-visitor and survives conversation resets (max 24 entries).
- **Working memory (facts)** is extracted every turn (max 12 facts) and resets with the conversation.
- **Notes** saved via `saveToFile` are written to the server's notes directory.
- **Briefings** are stored in `briefings.json` with daily snapshots and run history.

## How do I reset my conversation?

Click the "New Conversation" button in the chat UI. This clears short-term memory and the message history but preserves long-term memory.

## Does AIAC support multiple users?

The app identifies users by a browser cookie (`visitor-id`). Each visitor gets their own conversation history, memory, and profile. There are no user accounts or login — identity is cookie-based.
