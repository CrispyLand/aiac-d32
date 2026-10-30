# API Reference

## HTTP API (port 8080)

### POST /api/chat
Send a message to the local LLM (Ollama).

**Request:**
```json
{
  "message": "What is Spring Boot?",
  "model": "qwen2.5:0.5b",
  "system": "You are a helpful assistant.",
  "temperature": 0.7,
  "maxTokens": 400
}
```
Only `message` is required. Other fields have defaults.

**Response (200):**
```json
{
  "reply": "Spring Boot is a framework...",
  "model": "qwen2.5:0.5b",
  "latencyMs": 1500,
  "promptTokens": 42,
  "completionTokens": 85,
  "queueSize": 0,
  "queueMax": 5
}
```

**Error (429 — queue full):**
```json
{"error": "Ollama queue is full (5/5). Please try again in a moment."}
```

### GET /api/status
Queue and model info.

**Response:**
```json
{"ollama": true, "model": "qwen2.5:0.5b", "queue": 0, "queueMax": 5}
```

### GET /ollama/queue
Queue status for the chat UI.

**Response:**
```json
{"enabled": true, "active": true, "size": 0, "max": 5}
```

## Web UI Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Chat page |
| POST | `/` | Send a chat message |
| POST | `/llm/provider` | Switch between Cerebras and Ollama |
| POST | `/reset` | Clear conversation |
| POST | `/branch/switch` | Switch conversation branch |
| POST | `/profile/switch` | Switch active persona |
| POST | `/rag/toggle` | Toggle RAG mode |
| POST | `/rag/upload` | Upload files for RAG indexing |
| GET | `/indexer` | RAG index management page |

## MCP Tools (port 8081)

Tools exposed via the Model Context Protocol:

### Calendar & Tasks
- **getSchedule(date)** — Google Calendar events for a given day (yyyy-MM-dd)
- **getTasks(date?)** — Google Tasks due on or before date

### Notes
- **summarize(text, instructions?)** — AI text processing
- **saveToFile(content, filename)** — writes to /opt/aiac/notes/

### Briefing
- **getBriefing(date?)** — cached briefing summary
- **getTrend(days?)** — multi-day trend analysis

### Git (Day 31)
- **getGitBranch()** — current branch + last 5 commits
- **getGitFiles(path?)** — list tracked files (optional subdirectory filter)
- **getGitDiff(file?)** — uncommitted changes (optional file filter)
- **getGitStatus()** — working tree status (modified, added, untracked)

## Weather Server (port 8082)
- **getWeather(city?)** — current weather from Open-Meteo

## Telegram Bot Commands
- `/start` — welcome message
- `/help` — usage tips
- `/status` — queue info, rate limits, user ID
- `/kill` — (admin) disable bot
- `/resume` — (admin) re-enable bot

## Gatekeeper concept quizzes

Available when `gatekeeper.enabled` and `gatekeeper.quiz-enabled` are true. These
pre-authored quizzes use deterministic grading and do not call an LLM or unlock gates.

- `POST /api/gatekeeper/quiz/start` accepts `{ "sessionId": "...", "trigger": "remedial" }`
  or `"reinforcement"`. Remedial quizzes follow the latest failed gate and prioritize
  missing criteria; reinforcement reviews the gate just passed, including gate 6.
  Returns `quizId`, `gate`, `trigger`, and `questions`, or `{ "quizAvailable": false }`.
  Correct answers are excluded. Repeated starts resume the same unfinished quiz,
  including after an application restart. Completed quizzes are not presented again
  for the same gate outcome.
- `POST /api/gatekeeper/quiz/submit` accepts `{ "sessionId": "...", "quizId": "...",
  "answers": [{ "questionId": "...", "answer": "b" }] }`.
  Answer values are option keys for multiple choice, `"true"`/`"false"` for true/false,
  and a JSON-encoded array of right-hand labels in pair order for matching (for example,
  `"[\"Product\",\"Customer need\"]"`). Every question must be answered exactly once.
  Returns `score`, `total`, and per-question results, explanations, and textbook references.
  A quiz belongs to its original session and gate; retrying a completed submission
  returns its original result without appending another completion event.

Question counts come from `quiz-remedial-count` and `quiz-reinforcement-count`
(default 3 each, capped by the available pool). Attempts and individual answers are
persisted in the session event log, appear in the teacher transcript, and inform
the tutor's follow-up on missed concepts. Refreshing an incomplete remedial quiz
resumes it; reinforcement quizzes can be skipped.

Run UI interaction tests with `node --test src/test/js/*.test.cjs`. GitHub Actions
runs these before the Maven checks.
